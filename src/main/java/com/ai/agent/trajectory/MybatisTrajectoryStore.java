package com.ai.agent.trajectory;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentLlmAttemptEntity;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.persistence.mapper.AgentEventMapper;
import com.ai.agent.persistence.mapper.AgentLlmAttemptMapper;
import com.ai.agent.persistence.mapper.AgentMessageMapper;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.persistence.mapper.AgentToolCallTraceMapper;
import com.ai.agent.persistence.mapper.AgentToolProgressMapper;
import com.ai.agent.persistence.mapper.AgentToolResultTraceMapper;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.util.Ids;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class MybatisTrajectoryStore implements TrajectoryStore, TrajectoryReader {
    private static final TypeReference<List<ToolCallMessage>> TOOL_CALLS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentRunMapper runMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentLlmAttemptMapper llmAttemptMapper;
    private final AgentToolCallTraceMapper toolCallMapper;
    private final AgentToolResultTraceMapper toolResultMapper;
    private final AgentEventMapper eventMapper;
    private final AgentToolProgressMapper progressMapper;
    private final ObjectMapper objectMapper;

    public MybatisTrajectoryStore(
            AgentRunMapper runMapper,
            AgentMessageMapper messageMapper,
            AgentLlmAttemptMapper llmAttemptMapper,
            AgentToolCallTraceMapper toolCallMapper,
            AgentToolResultTraceMapper toolResultMapper,
            AgentEventMapper eventMapper,
            AgentToolProgressMapper progressMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
        this.llmAttemptMapper = llmAttemptMapper;
        this.toolCallMapper = toolCallMapper;
        this.toolResultMapper = toolResultMapper;
        this.eventMapper = eventMapper;
        this.progressMapper = progressMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void createRun(String runId, String userId) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setUserId(userId);
        entity.setStatus(RunStatus.CREATED.name());
        entity.setTurnNo(0);
        runMapper.insert(entity);
    }

    @Override
    public void updateRunStatus(String runId, RunStatus status, String error) {
        runMapper.updateStatus(runId, status.name(), error);
    }

    @Override
    public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
        return runMapper.transitionStatus(runId, expected.name(), next.name(), error) == 1;
    }

    @Override
    public int nextTurn(String runId) {
        runMapper.incrementTurn(runId);
        return currentTurn(runId);
    }

    @Override
    public int currentTurn(String runId) {
        Integer turnNo = runMapper.currentTurn(runId);
        return turnNo == null ? 0 : turnNo;
    }

    @Override
    public String findRunUserId(String runId) {
        return runMapper.findUserId(runId);
    }

    @Override
    public RunStatus findRunStatus(String runId) {
        return RunStatus.valueOf(runMapper.findStatus(runId));
    }

    @Override
    public String appendMessage(String runId, LlmMessage message) {
        String messageId = message.messageId() == null ? Ids.newId("msg") : message.messageId();
        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setMessageId(messageId);
        entity.setRunId(runId);
        entity.setSeq(nextMessageSeq(runId));
        entity.setRole(message.role().name());
        entity.setContent(message.content());
        entity.setToolUseId(message.toolUseId());
        entity.setToolCalls(jsonOrNull(message.toolCalls().isEmpty() ? null : message.toolCalls()));
        entity.setExtras(jsonOrNull(message.extras().isEmpty() ? null : message.extras()));
        messageMapper.insert(entity);
        return messageId;
    }

    @Override
    public List<LlmMessage> loadMessages(String runId) {
        return messageMapper.findByRunId(runId).stream()
                .map(entity -> new LlmMessage(
                        entity.getMessageId(),
                        MessageRole.valueOf(entity.getRole()),
                        entity.getContent(),
                        readJson(entity.getToolCalls(), TOOL_CALLS_TYPE, List.of()),
                        entity.getToolUseId(),
                        readJson(entity.getExtras(), MAP_TYPE, Map.of())
                ))
                .toList();
    }

    @Override
    public void writeLlmAttempt(
            String attemptId,
            String runId,
            int turnNo,
            String provider,
            String model,
            String status,
            FinishReason finishReason,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String errorJson,
            String rawDiagnosticJson
    ) {
        AgentLlmAttemptEntity entity = new AgentLlmAttemptEntity();
        entity.setAttemptId(attemptId);
        entity.setRunId(runId);
        entity.setTurnNo(turnNo);
        entity.setProvider(provider);
        entity.setModel(model);
        entity.setStatus(status);
        entity.setFinishReason(finishReason == null ? null : finishReason.name());
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setTotalTokens(totalTokens);
        entity.setErrorJson(errorJson);
        entity.setRawDiagnosticJson(rawDiagnosticJson);
        entity.setCompletedAt(LocalDateTime.now());
        llmAttemptMapper.insert(entity);
    }

    @Override
    public void writeAgentEvent(String runId, String eventType, String payloadJson) {
        AgentEventEntity entity = new AgentEventEntity();
        entity.setEventId(Ids.newId("evt"));
        entity.setRunId(runId);
        entity.setEventType(eventType);
        entity.setPayloadJson(payloadJson);
        eventMapper.insert(entity);
    }

    @Override
    public void writeToolCall(String messageId, ToolCall call) {
        toolCallMapper.insert(toEntity(messageId, call));
    }

    @Override
    public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
        AgentToolResultTraceEntity entity = new AgentToolResultTraceEntity();
        entity.setResultId(Ids.newId("res"));
        entity.setToolCallId(terminal.toolCallId());
        entity.setRunId(runId);
        entity.setToolUseId(toolUseId);
        entity.setStatus(terminal.status().name());
        entity.setResultJson(terminal.resultJson());
        entity.setErrorJson(terminal.errorJson());
        entity.setCancelReason(terminal.cancelReason() == null ? null : terminal.cancelReason().name());
        entity.setSynthetic(terminal.synthetic());
        toolResultMapper.upsertTrace(entity);
    }

    @Override
    public List<ToolCall> findToolCallsByRun(String runId) {
        return toolCallMapper.findByRunId(runId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public TrajectorySnapshot loadTrajectorySnapshot(String runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        return new TrajectorySnapshot(
                run,
                messageMapper.findByRunId(runId),
                llmAttemptMapper.selectList(new LambdaQueryWrapper<AgentLlmAttemptEntity>()
                        .eq(AgentLlmAttemptEntity::getRunId, runId)
                        .orderByAsc(AgentLlmAttemptEntity::getTurnNo)
                        .orderByAsc(AgentLlmAttemptEntity::getStartedAt)),
                toolCallMapper.findByRunId(runId),
                toolResultMapper.selectList(new LambdaQueryWrapper<AgentToolResultTraceEntity>()
                        .eq(AgentToolResultTraceEntity::getRunId, runId)
                        .orderByAsc(AgentToolResultTraceEntity::getCreatedAt)),
                eventMapper.selectList(new LambdaQueryWrapper<AgentEventEntity>()
                        .eq(AgentEventEntity::getRunId, runId)
                        .orderByAsc(AgentEventEntity::getCreatedAt)),
                progressMapper.selectList(new LambdaQueryWrapper<AgentToolProgressEntity>()
                        .eq(AgentToolProgressEntity::getRunId, runId)
                        .orderByAsc(AgentToolProgressEntity::getCreatedAt))
        );
    }

    @Override
    @Transactional
    public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
        String messageId = appendMessage(runId, assistant);
        for (ToolCall call : toolCalls) {
            writeToolCall(messageId, call);
        }
        return messageId;
    }

    private Long nextMessageSeq(String runId) {
        Long seq = messageMapper.nextSeq(runId);
        return seq == null ? 1L : seq;
    }

    private AgentToolCallTraceEntity toEntity(String messageId, ToolCall call) {
        AgentToolCallTraceEntity entity = new AgentToolCallTraceEntity();
        entity.setToolCallId(call.toolCallId());
        entity.setRunId(call.runId());
        entity.setMessageId(messageId);
        entity.setSeq(call.seq());
        entity.setToolUseId(call.toolUseId());
        entity.setRawToolName(call.rawToolName());
        entity.setToolName(call.toolName());
        entity.setArgsJson(call.argsJson());
        entity.setConcurrent(call.isConcurrent());
        entity.setIdempotent(call.idempotent());
        entity.setPrecheckFailed(call.precheckFailed());
        entity.setPrecheckErrorJson(call.precheckErrorJson());
        return entity;
    }

    private ToolCall toDomain(AgentToolCallTraceEntity entity) {
        return new ToolCall(
                entity.getRunId(),
                entity.getToolCallId(),
                entity.getSeq(),
                entity.getToolUseId(),
                entity.getRawToolName(),
                entity.getToolName(),
                entity.getArgsJson(),
                Boolean.TRUE.equals(entity.getConcurrent()),
                Boolean.TRUE.equals(entity.getIdempotent()),
                Boolean.TRUE.equals(entity.getPrecheckFailed()),
                entity.getPrecheckErrorJson()
        );
    }

    private String jsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize json", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, T defaultValue) {
        if (json == null || json.isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read stored json", e);
        }
    }
}
