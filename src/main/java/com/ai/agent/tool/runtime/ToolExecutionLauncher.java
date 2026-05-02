package com.ai.agent.tool.runtime;

import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.web.sse.ToolProgressEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 工具执行启动器，负责调度和执行已就绪的工具。
 *
 * <p>从Redis队列获取待执行工具，通过线程池提交执行任务，
 * 处理执行结果并触发后续调度。支持执行被拒绝时的优雅降级处理。
 */
@Component
public final class ToolExecutionLauncher {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutionLauncher.class);

    private final RedisToolStore store;
    private final ToolRegistry toolRegistry;
    private final TrajectoryStore trajectoryStore;
    private final ToolResultPubSub toolResultPubSub;
    private final RunEventSinkRegistry sinkRegistry;
    private final ExecutorService toolExecutor;

    public ToolExecutionLauncher(
            RedisToolStore store,
            ToolRegistry toolRegistry,
            TrajectoryStore trajectoryStore,
            ToolResultPubSub toolResultPubSub,
            RunEventSinkRegistry sinkRegistry,
            @Qualifier("toolExecutor") ExecutorService toolExecutor
    ) {
        this.store = store;
        this.toolRegistry = toolRegistry;
        this.trajectoryStore = trajectoryStore;
        this.toolResultPubSub = toolResultPubSub;
        this.sinkRegistry = sinkRegistry;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 触发运行的调度执行。
     *
     * @param runId 运行标识符
     */
    public void drainRun(String runId) {
        launchScheduled(runId, store.schedule(runId));
    }

    /**
     * 启动已调度工具的执行。
     *
     * @param runId 运行标识符
     * @param startedTools 已调度的工具列表
     */
    public void launchScheduled(String runId, List<StartedTool> startedTools) {
        if (startedTools == null || startedTools.isEmpty()) {
            return;
        }
        log.info("tool scheduler started batch size={}", startedTools.size());
        for (StartedTool tool : startedTools) {
            try {
                Map<String, String> parentMdc = MDC.getCopyOfContextMap();
                toolExecutor.submit(() -> runWithMdc(parentMdc, () -> execute(tool)));
            } catch (RejectedExecutionException e) {
                completeRejected(tool, e);
            }
        }
    }

    /**
     * 处理执行被拒绝的工具。
     *
     * @param tool 被拒绝的工具
     * @param e 拒绝异常
     */
    private void completeRejected(StartedTool tool, RejectedExecutionException e) {
        log.error("tool execution rejected by executor toolName={} toolCallId={}", tool.call().toolName(), tool.call().toolCallId(), e);
        ToolTerminal terminal = ToolTerminal.syntheticCancelled(
                tool.call().toolCallId(),
                CancelReason.EXECUTOR_REJECTED,
                "{\"type\":\"executor_rejected\"}"
        );
        if (store.complete(tool, terminal)) {
            publishCompleted(tool);
        }
    }

    /**
     * 执行单个工具，设置MDC上下文。
     *
     * @param started 待执行的工具
     */
    private void execute(StartedTool started) {
        try (MDC.MDCCloseable ignoredRun = MDC.putCloseable("runId", started.call().runId());
             MDC.MDCCloseable ignoredTool = MDC.putCloseable("toolCallId", started.call().toolCallId())) {
            executeWithMdc(started);
        }
    }

    /**
     * 执行单个工具的核心逻辑。
     *
     * @param started 待执行的工具
     */
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
        if (store.complete(started, terminal)) {
            publishCompleted(started);
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

    /**
     * 发布工具执行完成通知。
     *
     * @param started 已完成的工具
     */
    private void publishCompleted(StartedTool started) {
        try {
            toolResultPubSub.publish(started.call().runId(), started.call().toolCallId());
        } catch (RuntimeException e) {
            log.warn("failed to publish tool result notification runId={} toolCallId={} error={}",
                    started.call().runId(), started.call().toolCallId(), e.getMessage());
        }
        sinkRegistry.find(started.call().runId()).ifPresent(sink -> {
            try {
                sink.onToolProgress(new ToolProgressEvent(
                        started.call().runId(),
                        started.call().toolCallId(),
                        "completed",
                        "工具执行完成，等待 agent loop 写入结果",
                        100
                ));
            } catch (RuntimeException e) {
                log.warn("failed to emit completed tool progress runId={} toolCallId={} error={}",
                        started.call().runId(), started.call().toolCallId(), e.getMessage());
            }
        });
    }

    /**
     * 在指定MDC上下文中运行任务。
     *
     * @param context MDC上下文
     * @param task 待执行任务
     */
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

    /**
     * 转义JSON字符串中的特殊字符。
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 空实现的AgentEventSink，用于无事件推送时的占位 */
    private enum AgentEventSinkNoop implements com.ai.agent.web.sse.AgentEventSink {
        INSTANCE;

        @Override
        public void onTextDelta(com.ai.agent.web.sse.TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(com.ai.agent.web.sse.ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(com.ai.agent.web.sse.ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(com.ai.agent.web.sse.ToolResultEvent event) {
        }

        @Override
        public void onFinal(com.ai.agent.web.sse.FinalEvent event) {
        }

        @Override
        public void onError(com.ai.agent.web.sse.ErrorEvent event) {
        }
    }
}
