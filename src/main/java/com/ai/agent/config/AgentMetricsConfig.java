package com.ai.agent.config;

import com.ai.agent.tool.runtime.redis.RedisToolStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent指标配置类。
 *
 * <p>用于配置Micrometer监控指标，包括活跃运行数量和线程池队列深度等指标，
 * 便于通过Prometheus等监控系统进行性能监控。
 *
 * @author ai-agent
 */
@Configuration
public class AgentMetricsConfig {
    /**
     * 构造函数，注册各类监控指标。
     *
     * <p>注册活跃运行数量指标和各执行器的队列深度指标到Micrometer注册表。
     *
     * @param registry Micrometer指标注册表
     * @param redisToolStore Redis工具存储，用于获取活跃运行信息
     * @param agentExecutor Agent执行器
     * @param toolExecutor 工具执行器
     * @param eventExecutor 事件执行器
     */
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

    /**
     * 注册执行器队列深度指标。
     *
     * <p>为ThreadPoolExecutor类型的执行器注册队列深度监控指标，
     * 指标名称为agent.executor.queue_depth，带有executor标签区分不同执行器。
     *
     * @param registry Micrometer指标注册表
     * @param executorName 执行器名称，用于标签区分
     * @param executor 执行器服务
     */
    private void registerQueueDepth(MeterRegistry registry, String executorName, ExecutorService executor) {
        if (executor instanceof ThreadPoolExecutor pool) {
            Gauge.builder("agent.executor.queue_depth", pool, value -> value.getQueue().size())
                    .tag("executor", executorName)
                    .register(registry);
        }
    }
}