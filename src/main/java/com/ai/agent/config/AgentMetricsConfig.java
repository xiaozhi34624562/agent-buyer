package com.ai.agent.config;

import com.ai.agent.tool.redis.RedisToolStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentMetricsConfig {
    public AgentMetricsConfig(
            MeterRegistry registry,
            RedisToolStore redisToolStore,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("toolExecutor") ExecutorService toolExecutor,
            @Qualifier("eventExecutor") ExecutorService eventExecutor
    ) {
        Gauge.builder("agent.active_runs", redisToolStore, store -> store.activeRunIds().size()).register(registry);
        registerQueueDepth(registry, "agent", agentExecutor);
        registerQueueDepth(registry, "tool", toolExecutor);
        registerQueueDepth(registry, "event", eventExecutor);
    }

    private void registerQueueDepth(MeterRegistry registry, String executorName, ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor pool) {
            Gauge.builder("agent.executor.queue_depth", pool, value -> value.getQueue().size())
                    .tag("executor", executorName)
                    .register(registry);
        }
    }
}
