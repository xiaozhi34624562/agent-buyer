package com.ai.agent.tool;

import com.ai.agent.tool.redis.RedisToolStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

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
        launcher.drainRun(runId);
    }
}
