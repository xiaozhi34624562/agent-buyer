package com.ai.agent.tool;

import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Component
public final class RedisToolRuntime implements ToolRuntime {
    private final RedisToolStore store;
    private final ToolRegistry toolRegistry;
    private final TrajectoryStore trajectoryStore;
    private final RunEventSinkRegistry sinkRegistry;
    private final ExecutorService toolExecutor;

    public RedisToolRuntime(
            RedisToolStore store,
            ToolRegistry toolRegistry,
            TrajectoryStore trajectoryStore,
            RunEventSinkRegistry sinkRegistry,
            @Qualifier("toolExecutor") ExecutorService toolExecutor
    ) {
        this.store = store;
        this.toolRegistry = toolRegistry;
        this.trajectoryStore = trajectoryStore;
        this.sinkRegistry = sinkRegistry;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public void onToolUse(String runId, ToolCall call) {
        if (!call.precheckFailed()) {
            store.ingestWaiting(runId, call);
        }
        drain(runId);
    }

    private void drain(String runId) {
        List<StartedTool> started = store.schedule(runId);
        for (StartedTool tool : started) {
            try {
                toolExecutor.submit(() -> execute(tool));
            } catch (RejectedExecutionException e) {
                ToolTerminal terminal = ToolTerminal.syntheticCancelled(
                        tool.call().toolCallId(),
                        CancelReason.EXECUTOR_REJECTED,
                        "{\"type\":\"executor_rejected\"}"
                );
                store.complete(tool, terminal);
                trajectoryStore.writeToolResult(tool.call().runId(), tool.call().toolUseId(), terminal);
            }
        }
    }

    private void execute(StartedTool started) {
        ToolTerminal terminal;
        try {
            Tool tool = toolRegistry.resolve(started.call().toolName());
            String userId = trajectoryStore.findRunUserId(started.call().runId());
            ToolExecutionContext ctx = new ToolExecutionContext(
                    started.call().runId(),
                    userId,
                    sinkRegistry.find(started.call().runId()).orElse(AgentEventSinkNoop.INSTANCE)
            );
            terminal = tool.run(ctx, started, () -> false);
        } catch (Exception e) {
            terminal = ToolTerminal.failed(
                    started.call().toolCallId(),
                    "{\"type\":\"tool_execution_failed\",\"message\":\"" + escape(e.getMessage()) + "\"}"
            );
        }
        boolean completed = store.complete(started, terminal);
        if (completed) {
            trajectoryStore.writeToolResult(started.call().runId(), started.call().toolUseId(), terminal);
        }
        drain(started.call().runId());
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
