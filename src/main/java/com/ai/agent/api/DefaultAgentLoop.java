package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.LlmUsage;
import com.ai.agent.llm.PromptAssembler;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.Tool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.ToolUse;
import com.ai.agent.tool.ToolUseContext;
import com.ai.agent.tool.ToolValidation;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public final class DefaultAgentLoop implements AgentLoop {
    private final AgentProperties properties;
    private final PromptAssembler promptAssembler;
    private final LlmProviderAdapter providerAdapter;
    private final TranscriptPairValidator transcriptPairValidator;
    private final ToolRegistry toolRegistry;
    private final ToolRuntime toolRuntime;
    private final ToolResultWaiter toolResultWaiter;
    private final TrajectoryStore trajectoryStore;
    private final RunEventSinkRegistry sinkRegistry;
    private final ContinuationLockService continuationLockService;
    private final ObjectMapper objectMapper;

    public DefaultAgentLoop(
            AgentProperties properties,
            PromptAssembler promptAssembler,
            LlmProviderAdapter providerAdapter,
            TranscriptPairValidator transcriptPairValidator,
            ToolRegistry toolRegistry,
            ToolRuntime toolRuntime,
            ToolResultWaiter toolResultWaiter,
            TrajectoryStore trajectoryStore,
            RunEventSinkRegistry sinkRegistry,
            ContinuationLockService continuationLockService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.promptAssembler = promptAssembler;
        this.providerAdapter = providerAdapter;
        this.transcriptPairValidator = transcriptPairValidator;
        this.toolRegistry = toolRegistry;
        this.toolRuntime = toolRuntime;
        this.toolResultWaiter = toolResultWaiter;
        this.trajectoryStore = trajectoryStore;
        this.sinkRegistry = sinkRegistry;
        this.continuationLockService = continuationLockService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRunResult run(String userId, AgentRunRequest request, AgentEventSink sink) {
        String runId = Ids.newId("run");
        trajectoryStore.createRun(runId, userId);
        sinkRegistry.bind(runId, sink);
        try {
            List<Tool> allowedTools = toolRegistry.allowed(request.allowedToolNames());
            trajectoryStore.appendMessage(runId, LlmMessage.system(Ids.newId("msg"), promptAssembler.materializeSystemPrompt(userId, allowedTools)));
            for (UserMessage message : request.messages()) {
                trajectoryStore.appendMessage(runId, LlmMessage.user(Ids.newId("msg"), message.content()));
            }
            trajectoryStore.updateRunStatus(runId, RunStatus.RUNNING, null);
            return runUntilStop(runId, userId, request.allowedToolNames(), request.llmParams(), sink);
        } finally {
            sinkRegistry.unbind(runId);
        }
    }

    @Override
    public AgentRunResult continueRun(String userId, String runId, UserMessage message, AgentEventSink sink) {
        ContinuationLockService.Lock lock = continuationLockService.acquire(runId);
        if (lock == null) {
            throw new IllegalStateException("run is already being continued");
        }
        sinkRegistry.bind(runId, sink);
        try {
            if (!userId.equals(trajectoryStore.findRunUserId(runId))) {
                throw new IllegalArgumentException("run does not belong to current user");
            }
            RunStatus status = trajectoryStore.findRunStatus(runId);
            if (status != RunStatus.WAITING_USER_CONFIRMATION) {
                throw new IllegalStateException("run is not waiting for continuation: " + status);
            }
            trajectoryStore.appendMessage(runId, LlmMessage.user(Ids.newId("msg"), message.content()));
            trajectoryStore.updateRunStatus(runId, RunStatus.RUNNING, null);
            return runUntilStop(runId, userId, null, null, sink);
        } finally {
            sinkRegistry.unbind(runId);
            continuationLockService.release(lock);
        }
    }

    private AgentRunResult runUntilStop(
            String runId,
            String userId,
            Set<String> allowedToolNames,
            LlmParams params,
            AgentEventSink sink
    ) {
        Instant deadline = Instant.now().plusMillis(properties.getAgentLoop().getRunWallclockTimeoutMs());
        int maxTurns = params == null ? properties.getAgentLoop().getMaxTurns() : params.effectiveMaxTurns(properties.getAgentLoop().getMaxTurns());
        List<Tool> allowedTools = toolRegistry.allowed(allowedToolNames);
        String model = params != null && params.model() != null && !params.model().isBlank()
                ? params.model()
                : properties.getLlm().getDeepseek().getDefaultModel();

        for (int i = 0; i < maxTurns; i++) {
            if (Instant.now().isAfter(deadline)) {
                trajectoryStore.updateRunStatus(runId, RunStatus.TIMEOUT, "run wallclock timeout");
                sink.onError(new ErrorEvent(runId, "run timeout"));
                return new AgentRunResult(runId, RunStatus.TIMEOUT, null);
            }

            int turnNo = trajectoryStore.nextTurn(runId);
            List<LlmMessage> messages = trajectoryStore.loadMessages(runId);
            transcriptPairValidator.validate(messages);
            String attemptId = Ids.newId("att");

            LlmStreamResult result;
            try {
                result = providerAdapter.streamChat(
                        new LlmChatRequest(
                                runId,
                                attemptId,
                                model,
                                params == null ? null : params.temperature(),
                                params == null ? null : params.maxTokens(),
                                messages,
                                allowedTools.stream().map(Tool::schema).toList()
                        ),
                        delta -> sink.onTextDelta(new TextDeltaEvent(runId, attemptId, delta))
                );
                LlmUsage usage = result.usage();
                trajectoryStore.writeLlmAttempt(
                        attemptId,
                        runId,
                        turnNo,
                        "deepseek",
                        model,
                        "SUCCEEDED",
                        result.finishReason(),
                        usage == null ? null : usage.promptTokens(),
                        usage == null ? null : usage.completionTokens(),
                        usage == null ? null : usage.totalTokens(),
                        null,
                        result.rawDiagnosticJson()
                );
            } catch (Exception e) {
                trajectoryStore.writeLlmAttempt(
                        attemptId,
                        runId,
                        turnNo,
                        "deepseek",
                        model,
                        "FAILED",
                        FinishReason.ERROR,
                        null,
                        null,
                        null,
                        errorJson("provider_failed", e.getMessage()),
                        null
                );
                trajectoryStore.updateRunStatus(runId, RunStatus.FAILED, e.getMessage());
                sink.onError(new ErrorEvent(runId, e.getMessage()));
                return new AgentRunResult(runId, RunStatus.FAILED, null);
            }

            if (result.toolCalls().isEmpty()) {
                trajectoryStore.appendMessage(runId, LlmMessage.assistant(Ids.newId("msg"), result.content(), List.of()));
                trajectoryStore.updateRunStatus(runId, RunStatus.SUCCEEDED, null);
                sink.onFinal(new FinalEvent(runId, result.content(), RunStatus.SUCCEEDED, null));
                return new AgentRunResult(runId, RunStatus.SUCCEEDED, result.content());
            }

            ToolCommit commit = commitAssistantToolCalls(runId, userId, result);
            for (ToolCall call : commit.allCalls()) {
                sink.onToolUse(new ToolUseEvent(runId, call.toolUseId(), call.toolName(), call.argsJson()));
            }
            for (ToolCall failed : commit.precheckFailedCalls()) {
                ToolTerminal terminal = new ToolTerminal(
                        failed.toolCallId(),
                        ToolStatus.FAILED,
                        null,
                        failed.precheckErrorJson(),
                        CancelReason.PRECHECK_FAILED,
                        true
                );
                trajectoryStore.writeToolResult(runId, failed.toolUseId(), terminal);
                trajectoryStore.appendMessage(runId, LlmMessage.tool(Ids.newId("msg"), failed.toolUseId(), failed.precheckErrorJson()));
                sink.onToolResult(new ToolResultEvent(runId, failed.toolUseId(), ToolStatus.FAILED, null, failed.precheckErrorJson()));
            }
            for (ToolCall valid : commit.executableCalls()) {
                toolRuntime.onToolUse(runId, valid);
            }

            List<ToolTerminal> terminals = commit.executableCalls().isEmpty()
                    ? List.of()
                    : toolResultWaiter.awaitResults(
                    runId,
                    commit.executableCalls(),
                    Duration.ofMillis(properties.getAgentLoop().getToolResultTimeoutMs())
            );
            Map<String, ToolCall> callsById = commit.allCalls().stream()
                    .collect(Collectors.toMap(ToolCall::toolCallId, Function.identity()));
            for (ToolTerminal terminal : terminals) {
                ToolCall call = callsById.get(terminal.toolCallId());
                String content = terminal.resultJson() != null ? terminal.resultJson() : terminal.errorJson();
                trajectoryStore.appendMessage(runId, LlmMessage.tool(Ids.newId("msg"), call.toolUseId(), content));
                sink.onToolResult(new ToolResultEvent(runId, call.toolUseId(), terminal.status(), terminal.resultJson(), terminal.errorJson()));
            }

            PendingConfirm pendingConfirm = findPendingConfirm(terminals);
            if (pendingConfirm != null) {
                trajectoryStore.updateRunStatus(runId, RunStatus.WAITING_USER_CONFIRMATION, null);
                sink.onFinal(new FinalEvent(
                        runId,
                        pendingConfirm.summary(),
                        RunStatus.WAITING_USER_CONFIRMATION,
                        "user_confirmation"
                ));
                return new AgentRunResult(runId, RunStatus.WAITING_USER_CONFIRMATION, pendingConfirm.summary());
            }
        }

        trajectoryStore.updateRunStatus(runId, RunStatus.FAILED, "max turns exceeded");
        sink.onError(new ErrorEvent(runId, "max turns exceeded"));
        return new AgentRunResult(runId, RunStatus.FAILED, null);
    }

    private ToolCommit commitAssistantToolCalls(String runId, String userId, LlmStreamResult result) {
        List<ToolCallMessage> assistantToolCalls = new ArrayList<>();
        List<ToolCall> allCalls = new ArrayList<>();
        List<ToolCall> executable = new ArrayList<>();
        List<ToolCall> precheckFailed = new ArrayList<>();
        long seq = trajectoryStore.findToolCallsByRun(runId).size() + 1L;
        for (ToolCallMessage raw : result.toolCalls()) {
            String toolCallId = Ids.newId("tc");
            ToolCall call;
            try {
                Tool tool = toolRegistry.resolve(raw.name());
                ToolValidation validation = tool.validate(new ToolUseContext(runId, userId), new ToolUse(raw.toolUseId(), raw.name(), raw.argsJson()));
                if (validation.accepted()) {
                    call = new ToolCall(
                            runId,
                            toolCallId,
                            seq++,
                            raw.toolUseId(),
                            raw.name(),
                            tool.schema().name(),
                            validation.normalizedArgsJson(),
                            tool.schema().isConcurrent(),
                            false,
                            null
                    );
                    executable.add(call);
                    assistantToolCalls.add(new ToolCallMessage(raw.toolUseId(), tool.schema().name(), validation.normalizedArgsJson()));
                } else {
                    call = new ToolCall(
                            runId,
                            toolCallId,
                            seq++,
                            raw.toolUseId(),
                            raw.name(),
                            tool.schema().name(),
                            raw.argsJson(),
                            tool.schema().isConcurrent(),
                            true,
                            validation.errorJson()
                    );
                    precheckFailed.add(call);
                    assistantToolCalls.add(new ToolCallMessage(raw.toolUseId(), tool.schema().name(), raw.argsJson()));
                }
            } catch (Exception e) {
                call = new ToolCall(
                        runId,
                        toolCallId,
                        seq++,
                        raw.toolUseId(),
                        raw.name(),
                        raw.name(),
                        raw.argsJson(),
                        false,
                        true,
                        errorJson("unknown_or_invalid_tool", e.getMessage())
                );
                precheckFailed.add(call);
                assistantToolCalls.add(raw);
            }
            allCalls.add(call);
        }
        trajectoryStore.appendAssistantAndToolCalls(
                runId,
                LlmMessage.assistant(Ids.newId("msg"), result.content(), assistantToolCalls),
                allCalls
        );
        return new ToolCommit(allCalls, executable, precheckFailed);
    }

    private PendingConfirm findPendingConfirm(List<ToolTerminal> terminals) {
        for (ToolTerminal terminal : terminals) {
            if (terminal.resultJson() == null || terminal.resultJson().isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(terminal.resultJson());
                if ("PENDING_CONFIRM".equals(root.path("actionStatus").asText())) {
                    return new PendingConfirm(root.path("summary").asText("请确认是否继续。"));
                }
            } catch (Exception ignored) {
                // Non-JSON tool results are allowed; they simply cannot request confirmation.
            }
        }
        return null;
    }

    private String errorJson(String type, String message) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(Map.of(
                    "type", type,
                    "message", message == null ? "" : message
            )));
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    private record ToolCommit(List<ToolCall> allCalls, List<ToolCall> executableCalls, List<ToolCall> precheckFailedCalls) {
    }

    private record PendingConfirm(String summary) {
    }
}
