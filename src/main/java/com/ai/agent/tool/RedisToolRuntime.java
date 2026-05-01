package com.ai.agent.tool;

import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Component
public final class RedisToolRuntime implements ToolRuntime {
    private static final Logger log = LoggerFactory.getLogger(RedisToolRuntime.class);

    private final RedisToolStore store;
    private final ToolRegistry toolRegistry;
    private final TrajectoryStore trajectoryStore;
    private final ToolResultCloser toolResultCloser;
    private final RunEventSinkRegistry sinkRegistry;
    private final ExecutorService toolExecutor;

    public RedisToolRuntime(
            RedisToolStore store,
            ToolRegistry toolRegistry,
            TrajectoryStore trajectoryStore,
            ToolResultCloser toolResultCloser,
            RunEventSinkRegistry sinkRegistry,
            @Qualifier("toolExecutor") ExecutorService toolExecutor
    ) {
        this.store = store;
        this.toolRegistry = toolRegistry;
        this.trajectoryStore = trajectoryStore;
        this.toolResultCloser = toolResultCloser;
        this.sinkRegistry = sinkRegistry;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public void onToolUse(String runId, ToolCall call) {
        try (MDC.MDCCloseable ignoredRun = MDC.putCloseable("runId", runId);
             MDC.MDCCloseable ignoredTool = MDC.putCloseable("toolCallId", call.toolCallId())) {
            if (!call.precheckFailed()) {
                boolean ingested = store.ingestWaiting(runId, call);
                log.info(
                        "tool call ingested toolName={} toolUseId={} seq={} concurrent={} ingested={}",
                        call.toolName(),
                        call.toolUseId(),
                        call.seq(),
                        call.isConcurrent(),
                        ingested
                );
            }
            drainRun(runId);
        }
    }

    public void drainRun(String runId) {
        List<StartedTool> started = store.schedule(runId);
        if (!started.isEmpty()) {
            log.info("tool scheduler started batch size={}", started.size());
        }
        for (StartedTool tool : started) {
            try {
                Map<String, String> parentMdc = MDC.getCopyOfContextMap();
                toolExecutor.submit(() -> runWithMdc(parentMdc, () -> execute(tool)));
            } catch (RejectedExecutionException e) {
                log.error("tool execution rejected by executor toolName={} toolCallId={}", tool.call().toolName(), tool.call().toolCallId(), e);
                ToolTerminal terminal = ToolTerminal.syntheticCancelled(
                        tool.call().toolCallId(),
                        CancelReason.EXECUTOR_REJECTED,
                        "{\"type\":\"executor_rejected\"}"
                );
                if (store.complete(tool, terminal)) {
                    toolResultCloser.closeTerminal(
                            tool.call().runId(),
                            tool.call(),
                            terminal,
                            sinkRegistry.find(tool.call().runId()).orElse(AgentEventSinkNoop.INSTANCE)
                    );
                }
            }
        }
    }

    private void execute(StartedTool started) {
        try (MDC.MDCCloseable ignoredRun = MDC.putCloseable("runId", started.call().runId());
             MDC.MDCCloseable ignoredTool = MDC.putCloseable("toolCallId", started.call().toolCallId())) {
            executeWithMdc(started);
        }
    }

    private void executeWithMdc(StartedTool started) {
        ToolTerminal terminal;
        try {
            log.info(
                    "tool execution started toolName={} toolUseId={} attempt={} workerId={}",
                    started.call().toolName(),
                    started.call().toolUseId(),
                    started.attempt(),
                    started.workerId()
            );
            Tool tool = toolRegistry.resolve(started.call().toolName());
            String userId = trajectoryStore.findRunUserId(started.call().runId());
            ToolExecutionContext ctx = new ToolExecutionContext(
                    started.call().runId(),
                    userId,
                    sinkRegistry.find(started.call().runId()).orElse(AgentEventSinkNoop.INSTANCE)
            );
            terminal = tool.run(ctx, started, () -> store.abortRequested(started.call().runId()));
        } catch (Exception e) {
            log.error("tool execution failed toolName={} toolUseId={}", started.call().toolName(), started.call().toolUseId(), e);
            terminal = ToolTerminal.failed(
                    started.call().toolCallId(),
                    "{\"type\":\"tool_execution_failed\",\"message\":\"" + escape(e.getMessage()) + "\"}"
            );
        }
        boolean completed = store.complete(started, terminal);
        if (completed) {
            toolResultCloser.closeTerminal(
                    started.call().runId(),
                    started.call(),
                    terminal,
                    sinkRegistry.find(started.call().runId()).orElse(AgentEventSinkNoop.INSTANCE)
            );
            log.info(
                    "tool execution completed toolName={} toolUseId={} status={} synthetic={}",
                    started.call().toolName(),
                    started.call().toolUseId(),
                    terminal.status(),
                    terminal.synthetic()
            );
        } else {
            log.warn(
                    "tool completion rejected by redis CAS toolName={} toolUseId={} attempt={}",
                    started.call().toolName(),
                    started.call().toolUseId(),
                    started.attempt()
            );
        }
        drainRun(started.call().runId());
    }

    private void runWithMdc(Map<String, String> context, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (context == null || context.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            task.run();
        } finally {
            if (previous == null || previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private enum AgentEventSinkNoop implements com.ai.agent.api.AgentEventSink {
        INSTANCE;

        @Override
        public void onTextDelta(com.ai.agent.api.TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(com.ai.agent.api.ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(com.ai.agent.api.ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(com.ai.agent.api.ToolResultEvent event) {
        }

        @Override
        public void onFinal(com.ai.agent.api.FinalEvent event) {
        }

        @Override
        public void onError(com.ai.agent.api.ErrorEvent event) {
        }
    }
}
