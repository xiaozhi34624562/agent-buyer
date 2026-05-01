package com.ai.agent.tool.runtime;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public final class ToolLeaseReaper {
    private final AgentProperties properties;
    private final RedisToolStore store;
    private final RedisToolRuntime runtime;
    private final ToolResultCloser toolResultCloser;

    public ToolLeaseReaper(
            AgentProperties properties,
            RedisToolStore store,
            RedisToolRuntime runtime,
            ToolResultCloser toolResultCloser
    ) {
        this.properties = properties;
        this.store = store;
        this.runtime = runtime;
        this.toolResultCloser = toolResultCloser;
    }

    @Scheduled(
            initialDelayString = "${agent.reaper.interval-ms:10000}",
            fixedDelayString = "${agent.reaper.interval-ms:10000}"
    )
    public void reap() {
        if (!properties.getReaper().isEnabled()) {
            return;
        }
        long nowMillis = Instant.now().toEpochMilli();
        for (String runId : store.activeRunIds()) {
            var terminals = store.reapExpiredLeases(runId, nowMillis);
            if (!terminals.isEmpty()) {
                toolResultCloser.closeTerminals(runId, terminals, null);
            }
            runtime.drainRun(runId);
        }
    }
}
