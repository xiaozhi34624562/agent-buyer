package com.ai.agent.loop;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolStatus;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.ToolResultCloser;
import com.ai.agent.tool.runtime.ToolResultWaiter;
import com.ai.agent.tool.runtime.ToolRuntime;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ToolUseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class ToolCallCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ToolCallCoordinator.class);

    private final AgentProperties properties;
    private final ToolRegistry toolRegistry;
    private final ToolRuntime toolRuntime;
    private final RedisToolStore redisToolStore;
    private final ToolResultWaiter toolResultWaiter;
    private final TrajectoryStore trajectoryStore;
    private final TrajectoryReader trajectoryReader;
    private final ToolResultCloser toolResultCloser;
    private final PendingConfirmToolStore pendingConfirmToolStore;
    private final ObjectMapper objectMapper;

    public ToolCallCoordinator(
            AgentProperties properties,
            ToolRegistry toolRegistry,
            ToolRuntime toolRuntime,
            RedisToolStore redisToolStore,
            ToolResultWaiter toolResultWaiter,
            TrajectoryStore trajectoryStore,
            TrajectoryReader trajectoryReader,
            ToolResultCloser toolResultCloser,
            PendingConfirmToolStore pendingConfirmToolStore,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
        this.toolRuntime = toolRuntime;
        this.redisToolStore = redisToolStore;
        this.toolResultWaiter = toolResultWaiter;
        this.trajectoryStore = trajectoryStore;
        this.trajectoryReader = trajectoryReader;
        this.toolResultCloser = toolResultCloser;
        this.pendingConfirmToolStore = pendingConfirmToolStore;
        this.objectMapper = objectMapper;
    }

    public ToolStepResult processToolCalls(
            String runId,
            String userId,
            LlmStreamResult result,
            List<Tool> allowedTools,
            AgentEventSink sink
    ) {
        ToolCommit commit = commitAssistantToolCalls(runId, userId, result, allowedTools);
        log.info(
                "assistant tool calls committed total={} executable={} precheckFailed={}",
                commit.allCalls().size(),
                commit.executableCalls().size(),
                commit.precheckFailedCalls().size()
        );

        for (ToolCall call : commit.allCalls()) {
            sink.onToolUse(new ToolUseEvent(runId, call.toolUseId(), call.toolName(), call.argsJson()));
        }
        List<ToolTerminal> precheckTerminals = closePrecheckFailures(runId, commit.precheckFailedCalls(), sink);

        for (ToolCall valid : commit.executableCalls()) {
            toolRuntime.onToolUse(runId, valid);
        }

        List<ToolTerminal> terminals = new ArrayList<>(precheckTerminals);
        terminals.addAll(awaitAndCloseExecutableResults(runId, commit.executableCalls(), sink));
        return new ToolStepResult(
                commit.allCalls(),
                commit.executableCalls(),
                terminals,
                findPendingConfirm(runId, terminals, commit.allCalls()),
                findPendingUserInput(terminals)
        );
    }

    public List<ToolTerminal> abortRunTools(String runId, String reason, AgentEventSink sink) {
        List<ToolTerminal> terminals = redisToolStore.abort(runId, reason);
        toolResultCloser.closeTerminals(runId, terminals, sink);
        return terminals;
    }

    public List<ToolTerminal> interruptRunTools(String runId, String reason, AgentEventSink sink) {
        List<ToolTerminal> terminals = redisToolStore.interrupt(runId, reason);
        toolResultCloser.closeTerminals(runId, terminals, sink);
        return terminals;
    }

    public List<Tool> toolsFromContext(List<String> effectiveAllowedTools) {
        return effectiveAllowedTools.stream()
                .map(toolRegistry::resolve)
                .sorted(Comparator.comparing(tool -> tool.schema().name()))
                .toList();
    }

    private List<ToolTerminal> awaitAndCloseExecutableResults(
            String runId,
            List<ToolCall> executableCalls,
            AgentEventSink sink
    ) {
        if (executableCalls.isEmpty()) {
            return List.of();
        }
        try {
            List<ToolTerminal> terminals = toolResultWaiter.awaitResults(
                    runId,
                    executableCalls,
                    effectiveToolResultTimeout(properties, toolRegistry, executableCalls)
            );
            toolResultCloser.closeTerminals(runId, terminals, sink);
            return terminals;
        } catch (Exception e) {
            List<ToolTerminal> cancelled = abortRunTools(runId, "tool_result_timeout", sink);
            Set<String> cancelledIds = cancelled.stream()
                    .map(ToolTerminal::toolCallId)
                    .collect(Collectors.toSet());
            List<ToolCall> missing = executableCalls.stream()
                    .filter(call -> !cancelledIds.contains(call.toolCallId()))
                    .toList();
            toolResultCloser.closeSynthetic(
                    runId,
                    missing,
                    CancelReason.TOOL_TIMEOUT,
                    errorJson("tool_result_timeout", "tool result timeout"),
                    sink
            );
            throw new ToolResultTimeoutException("tool result timeout", e);
        }
    }

    static Duration effectiveToolResultTimeout(
            AgentProperties properties,
            ToolRegistry toolRegistry,
            List<ToolCall> executableCalls
    ) {
        Duration timeout = Duration.ofMillis(properties.getAgentLoop().getToolResultTimeoutMs());
        for (ToolCall call : executableCalls) {
            try {
                Duration toolTimeout = toolRegistry.resolve(call.toolName()).schema().timeout();
                if (toolTimeout.compareTo(timeout) > 0) {
                    timeout = toolTimeout;
                }
            } catch (RuntimeException e) {
                // Unknown tools are handled earlier during commit. Keep the global timeout as a defensive fallback.
            }
        }
        return timeout;
    }

    private List<ToolTerminal> closePrecheckFailures(String runId, List<ToolCall> precheckFailedCalls, AgentEventSink sink) {
        List<ToolTerminal> terminals = new ArrayList<>();
        for (ToolCall failed : precheckFailedCalls) {
            ToolTerminal terminal = new ToolTerminal(
                    failed.toolCallId(),
                    ToolStatus.FAILED,
                    null,
                    failed.precheckErrorJson(),
                    CancelReason.PRECHECK_FAILED,
                    true
            );
            toolResultCloser.closeTerminal(runId, failed, terminal, sink);
            terminals.add(terminal);
            log.warn("tool precheck failed toolName={} toolUseId={}", failed.toolName(), failed.toolUseId());
        }
        return terminals;
    }

    private ToolCommit commitAssistantToolCalls(
            String runId,
            String userId,
            LlmStreamResult result,
            List<Tool> allowedTools
    ) {
        Map<String, Tool> allowedToolsByCanonicalName = allowedToolsByCanonicalName(allowedTools);
        List<ToolCallMessage> assistantToolCalls = new ArrayList<>();
        List<ToolCall> allCalls = new ArrayList<>();
        List<ToolCall> executable = new ArrayList<>();
        List<ToolCall> precheckFailed = new ArrayList<>();
        long seq = trajectoryReader.findToolCallsByRun(runId).size() + 1L;
        for (ToolCallMessage raw : result.toolCalls()) {
            String toolCallId = Ids.newId("tc");
            ToolCall call;
            try {
                Tool tool = resolveAllowedTool(raw.name(), allowedToolsByCanonicalName);
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
                            tool.schema().idempotent(),
                            false,
                            null,
                            tool.schema().timeout().toMillis()
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
                            tool.schema().idempotent(),
                            true,
                            validation.errorJson(),
                            tool.schema().timeout().toMillis()
                    );
                    precheckFailed.add(call);
                    assistantToolCalls.add(new ToolCallMessage(raw.toolUseId(), tool.schema().name(), raw.argsJson()));
                }
            } catch (Exception e) {
                log.warn("tool call rejected before execution rawToolName={} toolUseId={} error={}", raw.name(), raw.toolUseId(), e.getMessage());
                call = new ToolCall(
                        runId,
                        toolCallId,
                        seq++,
                        raw.toolUseId(),
                        raw.name(),
                        raw.name(),
                        raw.argsJson(),
                        false,
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

    private Tool resolveAllowedTool(String rawName, Map<String, Tool> allowedToolsByCanonicalName) {
        Tool tool = allowedToolsByCanonicalName.get(ToolRegistry.canonicalize(rawName));
        if (tool == null) {
            throw new IllegalArgumentException("tool not allowed: " + rawName);
        }
        return tool;
    }

    private Map<String, Tool> allowedToolsByCanonicalName(List<Tool> allowedTools) {
        return allowedTools.stream()
                .collect(Collectors.toMap(
                        tool -> ToolRegistry.canonicalize(tool.schema().name()),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private PendingConfirm findPendingConfirm(String runId, List<ToolTerminal> terminals, List<ToolCall> allCalls) {
        // Build a map of toolCallId -> ToolCall for lookup
        Map<String, ToolCall> callsById = allCalls.stream()
                .collect(Collectors.toMap(ToolCall::toolCallId, Function.identity(), (left, right) -> left));

        for (ToolTerminal terminal : terminals) {
            if (terminal.resultJson() == null || terminal.resultJson().isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(terminal.resultJson());
                if ("PENDING_CONFIRM".equals(root.path("actionStatus").asText())) {
                    ToolCall call = callsById.get(terminal.toolCallId());
                    if (call == null) {
                        log.warn("PENDING_CONFIRM terminal has no matching tool call toolCallId={}", terminal.toolCallId());
                        continue;
                    }
                    String summary = root.path("summary").asText("请确认是否继续。");
                    String confirmToken = root.path("confirmToken").asText(null);
                    String orderId = root.path("orderId").asText(null);

                    // Build argsJson with confirmToken for execution
                    String argsJsonWithToken = buildArgsWithConfirmToken(call.argsJson(), confirmToken, orderId);

                    PendingConfirm pending = new PendingConfirm(
                            call.toolCallId(),
                            call.toolName(),
                            argsJsonWithToken,
                            confirmToken,
                            summary
                    );

                    // Save pending tool context for automatic execution on confirmation
                    pendingConfirmToolStore.save(
                            runId,
                            call.toolCallId(),
                            call.toolName(),
                            argsJsonWithToken,
                            confirmToken,
                            summary
                    );

                    return pending;
                }
            } catch (Exception ignored) {
                // Non-JSON tool results are allowed; they simply cannot request confirmation.
            }
        }
        return null;
    }

    /**
     * Build argsJson with confirmToken added.
     */
    private String buildArgsWithConfirmToken(String originalArgsJson, String confirmToken, String orderId) {
        try {
            JsonNode args = objectMapper.readTree(originalArgsJson);
            Map<String, Object> argsMap = new LinkedHashMap<>();
            args.fields().forEachRemaining(entry -> {
                try {
                    argsMap.put(entry.getKey(), objectMapper.treeToValue(entry.getValue(), Object.class));
                } catch (JsonProcessingException e) {
                    // Use raw string value as fallback
                    argsMap.put(entry.getKey(), entry.getValue().asText());
                }
            });
            if (confirmToken != null && !confirmToken.isBlank()) {
                argsMap.put("confirmToken", confirmToken);
            }
            if (orderId != null && !orderId.isBlank()) {
                argsMap.put("orderId", orderId);
            }
            return objectMapper.writeValueAsString(argsMap);
        } catch (Exception e) {
            log.warn("failed to build args with confirmToken: {}", e.getMessage());
            return originalArgsJson;
        }
    }

    private PendingUserInput findPendingUserInput(List<ToolTerminal> terminals) {
        for (ToolTerminal terminal : terminals) {
            PendingUserInput fromResult = pendingUserInputFromJson(terminal.resultJson());
            if (fromResult != null) {
                return fromResult;
            }
            PendingUserInput fromError = pendingUserInputFromJson(terminal.errorJson());
            if (fromError != null) {
                return fromError;
            }
        }
        return null;
    }

    private PendingUserInput pendingUserInputFromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.path("recoverable").asBoolean(false)) {
                return null;
            }
            if (!"user_input".equals(root.path("nextActionRequired").asText(""))) {
                return null;
            }
            String question = root.path("question").asText("");
            if (question.isBlank()) {
                question = root.path("message").asText("请补充必要信息后继续。");
            }
            return new PendingUserInput(question);
        } catch (Exception ignored) {
            return null;
        }
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

    public record ToolStepResult(
            List<ToolCall> allCalls,
            List<ToolCall> executableCalls,
            List<ToolTerminal> terminals,
            PendingConfirm pendingConfirm,
            PendingUserInput pendingUserInput
    ) {
    }

    public record PendingConfirm(
            String toolCallId,
            String toolName,
            String argsJson,
            String confirmToken,
            String summary
    ) {
    }

    public record PendingUserInput(String question) {
    }

    public static final class ToolResultTimeoutException extends RuntimeException {
        public ToolResultTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private record ToolCommit(
            List<ToolCall> allCalls,
            List<ToolCall> executableCalls,
            List<ToolCall> precheckFailedCalls
    ) {
    }
}
