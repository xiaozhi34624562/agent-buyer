package com.ai.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent健康检查配置类。
 *
 * <p>用于配置Spring Boot Actuator的健康检查指标，包括LLM提供商状态、
 * 线程池执行器状态和清理器状态等健康检查端点。
 *
 * @author ai-agent
 */
@Configuration
public class AgentHealthConfig {
    /**
     * 创建LLM提供商健康检查指示器。
     *
     * <p>检查DeepSeek API密钥是否配置正确，用于监控LLM服务的可用性。
     *
     * @param properties Agent配置属性
     * @return 提供商健康检查指示器
     */
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

    /**
     * 创建执行器健康检查指示器。
     *
     * <p>检查Agent、工具和事件三个线程池执行器的运行状态，
     * 包括是否关闭、线程池大小、活跃线程数和队列深度等指标。
     *
     * @param agentExecutor Agent执行器
     * @param toolExecutor 工具执行器
     * @param eventExecutor 事件执行器
     * @return 执行器健康检查指示器
     */
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

    /**
     * 创建清理器健康检查指示器。
     *
     * <p>检查清理器是否启用，用于监控过期运行清理任务的状态。
     *
     * @param properties Agent配置属性
     * @return 清理器健康检查指示器
     */
    @Bean
    HealthIndicator reaperHealthIndicator(AgentProperties properties) {
        return () -> properties.getReaper().isEnabled()
                ? Health.up().withDetail("enabled", true).build()
                : Health.unknown().withDetail("enabled", false).build();
    }

    /**
     * 添加执行器健康检查详情。
     *
     * <p>将执行器的状态信息添加到健康检查结果中，包括是否关闭、
     * 线程池大小、活跃线程数和队列深度等指标。
     *
     * @param builder 健康检查构建器
     * @param name 执行器名称
     * @param executor 执行器服务
     */
    private void addExecutor(Health.Builder builder, String name, ExecutorService executor) {
        builder.withDetail(name + ".shutdown", executor.isShutdown());
        if (executor instanceof ThreadPoolExecutor pool) {
            builder.withDetail(name + ".poolSize", pool.getPoolSize());
            builder.withDetail(name + ".active", pool.getActiveCount());
            builder.withDetail(name + ".queueDepth", pool.getQueue().size());
        }
    }
}