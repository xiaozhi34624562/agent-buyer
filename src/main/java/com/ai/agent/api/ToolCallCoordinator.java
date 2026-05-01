package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.Tool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.ToolUse;
import com.ai.agent.tool.ToolUseContext;
import com.ai.agent.tool.ToolValidation;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        closePrecheckFailures(runId, commit.precheckFailedCalls(), sink);

        for (ToolCall valid : commit.executableCalls()) {
            toolRuntime.onToolUse(runId, valid);
        }

        List<ToolTerminal> terminals = awaitAndCloseExecutableResults(runId, commit.executableCalls(), sink);
        return new ToolStepResult(commit.allCalls(), commit.executableCalls(), terminals, findPendingConfirm(terminals));
    }

    public List<ToolTerminal> abortRunTools(String runId, String reason, AgentEventSink sink) {
        List<ToolTerminal> terminals = redisToolStore.abort(runId, reason);
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
                    Duration.ofMillis(properties.getAgentLoop().getToolResultTimeoutMs())
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

    private void closePrecheckFailures(String runId, List<ToolCall> precheckFailedCalls, AgentEventSink sink) {
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
            log.warn("tool precheck failed toolName={} toolUseId={}", failed.toolName(), failed.toolUseId());
        }
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
                            tool.schema().idempotent(),
                            true,
                            validation.errorJson()
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

    public record ToolStepResult(
            List<ToolCall> allCalls,
            List<ToolCall> executableCalls,
            List<ToolTerminal> terminals,
            PendingConfirm pendingConfirm
    ) {
    }

    public record PendingConfirm(String summary) {
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
