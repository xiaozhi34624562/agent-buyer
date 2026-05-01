package com.ai.agent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public final class ToolLeaseReaper {
    private final AgentProperties properties;
    private final RedisToolStore store;
    private final RedisToolRuntime runtime;
    private final TrajectoryStore trajectoryStore;

    public ToolLeaseReaper(
            AgentProperties properties,
            RedisToolStore store,
            RedisToolRuntime runtime,
            TrajectoryStore trajectoryStore
    ) {
        this.properties = properties;
        this.store = store;
        this.runtime = runtime;
        this.trajectoryStore = trajectoryStore;
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
                Map<String, ToolCall> calls = trajectoryStore.findToolCallsByRun(runId).stream()
                        .collect(Collectors.toMap(ToolCall::toolCallId, Function.identity(), (left, right) -> left));
                for (ToolTerminal terminal : terminals) {
                    ToolCall call = calls.get(terminal.toolCallId());
                    if (call != null) {
                        trajectoryStore.writeToolResult(runId, call.toolUseId(), terminal);
                    }
                }
            }
            runtime.drainRun(runId);
        }
    }
}
