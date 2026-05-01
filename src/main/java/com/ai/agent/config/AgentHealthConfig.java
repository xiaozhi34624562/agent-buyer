package com.ai.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentHealthConfig {
    @Bean
    HealthIndicator providerHealthIndicator(AgentProperties properties) {
        return () -> {
            String apiKey = properties.getLlm().getDeepseek().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return Health.down().withDetail("provider", "deepseek").withDetail("reason", "api key missing").build();
            }
            return Health.up().withDetail("provider", "deepseek").build();
        };
    }

    @Bean
    HealthIndicator executorHealthIndicator(
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("toolExecutor") ExecutorService toolExecutor,
            @Qualifier("eventExecutor") ExecutorService eventExecutor
    ) {
        return () -> {
            boolean up = !agentExecutor.isShutdown() && !toolExecutor.isShutdown() && !eventExecutor.isShutdown();
            Health.Builder builder = up ? Health.up() : Health.down();
            addExecutor(builder, "agent", agentExecutor);
            addExecutor(builder, "tool", toolExecutor);
            addExecutor(builder, "event", eventExecutor);
            return builder.build();
        };
    }

    @Bean
    HealthIndicator reaperHealthIndicator(AgentProperties properties) {
        return () -> properties.getReaper().isEnabled()
                ? Health.up().withDetail("enabled", true).build()
                : Health.unknown().withDetail("enabled", false).build();
    }

    private void addExecutor(Health.Builder builder, String name, ExecutorService executor) {
        builder.withDetail(name + ".shutdown", executor.isShutdown());
        if (executor instanceof ThreadPoolExecutor pool) {
            builder.withDetail(name + ".poolSize", pool.getPoolSize());
            builder.withDetail(name + ".active", pool.getActiveCount());
            builder.withDetail(name + ".queueDepth", pool.getQueue().size());
        }
    }
}
