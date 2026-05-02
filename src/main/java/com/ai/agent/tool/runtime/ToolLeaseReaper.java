package com.ai.agent.tool.runtime;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 工具租约回收器，定期清理过期的工作租约。
 *
 * <p>检查运行中的工具租约是否过期，回收过期租约并触发结果关闭，
 * 确保系统资源及时释放，避免僵尸执行占用资源。
 */
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

    /**
     * 定时回收过期租约。
     *
     * <p>扫描所有活跃运行的租约，回收过期租约并关闭结果。
     */
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
