package com.ai.agent.tool.runtime;

import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Redis实现的工具运行时，负责工具调用请求的接入和调度触发。
 *
 * <p>接收工具调用请求，将工具存入Redis队列，并触发执行调度。
 */
@Component
public final class RedisToolRuntime implements ToolRuntime {
    private static final Logger log = LoggerFactory.getLogger(RedisToolRuntime.class);

    private final RedisToolStore store;
    private final ToolExecutionLauncher launcher;

    public RedisToolRuntime(
            RedisToolStore store,
            ToolExecutionLauncher launcher
    ) {
        this.store = store;
        this.launcher = launcher;
    }

    /**
     * 处理工具调用请求。
     *
     * <p>将工具调用存入等待队列，并触发调度执行。
     *
     * @param runId 运行标识符
     * @param call 工具调用请求
     */
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

    /**
     * 触发运行的调度执行。
     *
     * @param runId 运行标识符
     */
    public void drainRun(String runId) {
        launcher.drainRun(runId);
    }
}
