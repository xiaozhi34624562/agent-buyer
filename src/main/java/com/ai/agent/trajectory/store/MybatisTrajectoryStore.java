package com.ai.agent.trajectory.store;

/**
 * Mybatis 轨迹存储实现。
 * <p>
 * 基于 Mybatis 实现轨迹数据的持久化存储，包括运行、消息、工具调用等。
 * </p>
 */

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentLlmAttemptEntity;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.persistence.mapper.AgentContextCompactionMapper;
import com.ai.agent.persistence.mapper.AgentEventMapper;
import com.ai.agent.persistence.mapper.AgentLlmAttemptMapper;
import com.ai.agent.persistence.mapper.AgentMessageMapper;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.persistence.mapper.AgentToolCallTraceMapper;
import com.ai.agent.persistence.mapper.AgentToolProgressMapper;
import com.ai.agent.persistence.mapper.AgentToolResultTraceMapper;
import com.ai.agent.subagent.model.ParentLinkStatus;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.trajectory.model.ChildRunCreation;
import com.ai.agent.trajectory.model.TrajectorySnapshot;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MybatisTrajectoryStore implements TrajectoryStore, TrajectoryReader {

    /** 追加消息最大重试次数 */
    private static final int APPEND_MESSAGE_MAX_RETRIES = 8;

    /** 工具调用消息列表类型引用 */
    private static final TypeReference<List<ToolCallMessage>> TOOL_CALLS_TYPE = new TypeReference<>() {
    };

    /** Map 类型引用 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 运行 Mapper */
    private final AgentRunMapper runMapper;

    /** 消息 Mapper */
    private final AgentMessageMapper messageMapper;

    /** LLM 调用 Mapper */
    private final AgentLlmAttemptMapper llmAttemptMapper;

    /** 工具调用 Mapper */
    private final AgentToolCallTraceMapper toolCallMapper;

    /** 工具结果 Mapper */
    private final AgentToolResultTraceMapper toolResultMapper;

    /** 事件 Mapper */
    private final AgentEventMapper eventMapper;

    /** 工具进度 Mapper */
    private final AgentToolProgressMapper progressMapper;

    /** 压缩 Mapper */
    private final AgentContextCompactionMapper compactionMapper;

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param runMapper        运行 Mapper
     * @param messageMapper    消息 Mapper
     * @param llmAttemptMapper LLM 调用 Mapper
     * @param toolCallMapper   工具调用 Mapper
     * @param toolResultMapper 工具结果 Mapper
     * @param eventMapper      事件 Mapper
     * @param progressMapper   工具进度 Mapper
     * @param compactionMapper 压缩 Mapper
     * @param objectMapper     JSON 对象映射器
     */
    public MybatisTrajectoryStore(
            AgentRunMapper runMapper,
            AgentMessageMapper messageMapper,
            AgentLlmAttemptMapper llmAttemptMapper,
            AgentToolCallTraceMapper toolCallMapper,
            AgentToolResultTraceMapper toolResultMapper,
            AgentEventMapper eventMapper,
            AgentToolProgressMapper progressMapper,
            AgentContextCompactionMapper compactionMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
        this.llmAttemptMapper = llmAttemptMapper;
        this.toolCallMapper = toolCallMapper;
        this.toolResultMapper = toolResultMapper;
        this.eventMapper = eventMapper;
        this.progressMapper = progressMapper;
        this.compactionMapper = compactionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建新运行。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     */
    @Override
    public void createRun(String runId, String userId) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setUserId(userId);
        entity.setStatus(RunStatus.CREATED.name());
        entity.setTurnNo(0);
        entity.setAgentType("MAIN");
        runMapper.insert(entity);
    }

    /**
     * 创建子运行。
     *
     * @param runId             子运行标识
     * @param userId            用户标识
     * @param parentRunId       父运行标识
     * @param parentToolCallId  父工具调用标识
     * @param agentType         Agent 类型
     * @param parentLinkStatus  父链接状态
     * @return 创建结果
     */
    @Override
    public ChildRunCreation createChildRun(
            String runId,
            String userId,
            String parentRunId,
            String parentToolCallId,
            String agentType,
            String parentLinkStatus
    ) {
        AgentRunEntity existing = findExistingChildByParentToolCall(parentToolCallId);
        if (existing != null) {
            return new ChildRunCreation(existing.getRunId(), false);
        }
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setUserId(userId);
        entity.setStatus(RunStatus.CREATED.name());
        entity.setTurnNo(0);
        entity.setParentRunId(parentRunId);
        entity.setParentToolCallId(parentToolCallId);
        entity.setAgentType(canonicalChildAgentType(agentType));
        entity.setParentLinkStatus(canonicalParentLinkStatus(parentLinkStatus));
        try {
            runMapper.insert(entity);
            return new ChildRunCreation(runId, true);
        } catch (DuplicateKeyException e) {
            existing = findExistingChildByParentToolCall(parentToolCallId);
            if (existing != null) {
                return new ChildRunCreation(existing.getRunId(), false);
            }
            throw e;
        }
    }

    /**
     * 更新父链接状态。
     *
     * @param childRunId       子运行标识
     * @param parentLinkStatus 父链接状态
     */
    @Override
    public void updateParentLinkStatus(String childRunId, String parentLinkStatus) {
        runMapper.updateParentLinkStatus(childRunId, canonicalParentLinkStatus(parentLinkStatus));
    }

    /**
     * 更新运行状态。
     *
     * @param runId  运行标识
     * @param status 新状态
     * @param error  错误信息
     */
    @Override
    public void updateRunStatus(String runId, RunStatus status, String error) {
        runMapper.updateStatus(runId, status.name(), error);
    }

    /**
     * 状态转换（CAS 操作）。
     *
     * @param runId    运行标识
     * @param expected 期望状态
     * @param next     新状态
     * @param error    错误信息
     * @return 是否转换成功
     */
    @Override
    public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
        return runMapper.transitionStatus(runId, expected.name(), next.name(), error) == 1;
    }

    /**
     * 进入下一轮次。
     *
     * @param runId 运行标识
     * @return 新轮次号
     */
    @Override
    public int nextTurn(String runId) {
        runMapper.incrementTurn(runId);
        return currentTurn(runId);
    }

    /**
     * 获取当前轮次号。
     *
     * @param runId 运行标识
     * @return 当前轮次号
     */
    @Override
    public int currentTurn(String runId) {
        Integer turnNo = runMapper.currentTurn(runId);
        return turnNo == null ? 0 : turnNo;
    }

    /**
     * 查找运行的用户标识。
     *
     * @param runId 运行标识
     * @return 用户标识
     */
    @Override
    public String findRunUserId(String runId) {
        return runMapper.findUserId(runId);
    }

    /**
     * 查找运行状态。
     *
     * @param runId 运行标识
     * @return 运行状态
     */
    @Override
    public RunStatus findRunStatus(String runId) {
        return RunStatus.valueOf(runMapper.findStatus(runId));
    }

    /**
     * 追加消息。
     *
     * @param runId  运行标识
     * @param message 消息
     * @return 消息标识
     */
    @Override
    public String appendMessage(String runId, LlmMessage message) {
        String messageId = message.messageId() == null ? Ids.newId("msg") : message.messageId();
        String toolCallsJson = jsonOrNull(message.toolCalls().isEmpty() ? null : message.toolCalls());
        String extrasJson = jsonOrNull(message.extras().isEmpty() ? null : message.extras());
        for (int attempt = 1; attempt <= APPEND_MESSAGE_MAX_RETRIES; attempt++) {
            AgentMessageEntity entity = new AgentMessageEntity();
            entity.setMessageId(messageId);
            entity.setRunId(runId);
            entity.setSeq(nextMessageSeq(runId));
            entity.setRole(message.role().name());
            entity.setContent(message.content());
            entity.setToolUseId(message.toolUseId());
            entity.setToolCalls(toolCallsJson);
            entity.setExtras(extrasJson);
            try {
                messageMapper.insert(entity);
                return messageId;
            } catch (DuplicateKeyException e) {
                if (attempt == APPEND_MESSAGE_MAX_RETRIES) {
                    throw e;
                }
            }
        }
        return messageId;
    }

    /**
     * 加载运行的所有消息。
     *
     * @param runId 运行标识
     * @return 消息列表
     */
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

    /**
     * 记录 LLM 调用尝试。
     *
     * @param attemptId        调用标识
     * @param runId            运行标识
     * @param turnNo           轮次号
     * @param provider         提供商
     * @param model            模型
     * @param status           状态
     * @param finishReason     结束原因
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数
     * @param errorJson        错误 JSON
     * @param rawDiagnosticJson 原始诊断 JSON
     */
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

    /**
     * 记录 Agent 事件。
     *
     * @param runId        运行标识
     * @param eventType    事件类型
     * @param payloadJson 事件数据 JSON
     */
    @Override
    public void writeAgentEvent(String runId, String eventType, String payloadJson) {
        AgentEventEntity entity = new AgentEventEntity();
        entity.setEventId(Ids.newId("evt"));
        entity.setRunId(runId);
        entity.setEventType(eventType);
        entity.setPayloadJson(payloadJson);
        eventMapper.insert(entity);
    }

    /**
     * 记录工具调用。
     *
     * @param messageId 消息标识
     * @param call      工具调用
     */
    @Override
    public void writeToolCall(String messageId, ToolCall call) {
        toolCallMapper.insert(toEntity(messageId, call));
    }

    /**
     * 记录工具结果。
     *
     * @param runId     运行标识
     * @param toolUseId 工具使用标识
     * @param terminal  工具终端结果
     */
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

    /**
     * 查找运行的所有工具调用。
     *
     * @param runId 运行标识
     * @return 工具调用列表
     */
    @Override
    public List<ToolCall> findToolCallsByRun(String runId) {
        return toolCallMapper.findByRunId(runId).stream()
                .map(this::toDomain)
                .toList();
    }

    /**
     * 加载轨迹快照。
     *
     * @param runId 运行标识
     * @return 轨迹快照
     */
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
                        .orderByAsc(AgentToolProgressEntity::getCreatedAt)),
                compactionMapper.findByRunId(runId)
        );
    }

    /**
     * 追加助手消息和工具调用。
     *
     * @param runId     运行标识
     * @param assistant 助手消息
     * @param toolCalls 工具调用列表
     * @return 消息标识
     */
    @Override
    @Transactional
    public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
        String messageId = appendMessage(runId, assistant);
        for (ToolCall call : toolCalls) {
            writeToolCall(messageId, call);
        }
        return messageId;
    }

    /**
     * 获取下一个消息序号。
     *
     * @param runId 运行标识
     * @return 消息序号
     */
    private Long nextMessageSeq(String runId) {
        Long seq = messageMapper.nextSeq(runId);
        return seq == null ? 1L : seq;
    }

    /**
     * 转换工具调用领域对象为实体。
     *
     * @param messageId 消息标识
     * @param call      工具调用
     * @return 工具调用实体
     */
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

    /**
     * 转换工具调用实体为领域对象。
     *
     * @param entity 工具调用实体
     * @return 工具调用领域对象
     */
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

    /**
     * 序列化对象为 JSON 字符串。
     *
     * @param value 对象值
     * @return JSON 字符串
     */
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

    /**
     * 反序列化 JSON 字符串。
     *
     * @param json        JSON 字符串
     * @param type        类型引用
     * @param defaultValue 默认值
     * @return 反序列化后的对象
     */
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

    /**
     * 根据父工具调用标识查找已存在的子运行。
     *
     * @param parentToolCallId 父工具调用标识
     * @return 运行实体
     */
    private AgentRunEntity findExistingChildByParentToolCall(String parentToolCallId) {
        if (parentToolCallId == null || parentToolCallId.isBlank()) {
            return null;
        }
        return runMapper.findByParentToolCallId(parentToolCallId);
    }

    /**
     * 标准化子 Agent 类型。
     *
     * @param agentType Agent 类型
     * @return 标准化后的类型
     */
    private String canonicalChildAgentType(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("child agentType is required");
        }
        String canonical = agentType.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"explore".equals(canonical)) {
            throw new IllegalArgumentException("unknown child agentType: " + agentType);
        }
        return canonical;
    }

    /**
     * 标准化父链接状态。
     *
     * @param parentLinkStatus 父链接状态
     * @return 标准化后的状态
     */
    private String canonicalParentLinkStatus(String parentLinkStatus) {
        if (parentLinkStatus == null || parentLinkStatus.isBlank()) {
            return ParentLinkStatus.LIVE.name();
        }
        return ParentLinkStatus.valueOf(parentLinkStatus.trim().toUpperCase(java.util.Locale.ROOT)).name();
    }
}
