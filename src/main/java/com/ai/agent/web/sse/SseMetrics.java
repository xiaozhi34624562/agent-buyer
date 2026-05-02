package com.ai.agent.web.sse;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE 连接指标收集器。
 *
 * <p>收集和统计 SSE 连接相关指标，包括活跃连接数、连接打开/关闭次数、事件发送次数等。
 *
 * @author AI Agent
 */
@Component
public final class SseMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeConnections = new AtomicInteger();

    /**
     * 构造指标收集器并注册活跃连接数 Gauge。
     *
     * @param registry Micrometer 指标注册器
     */
    public SseMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("agent.sse.connections.active", activeConnections, AtomicInteger::get)
                .register(registry);
    }

    /**
     * 记录连接打开事件。
     */
    public void opened() {
        activeConnections.incrementAndGet();
        registry.counter("agent.sse.connections.opened").increment();
    }

    /**
     * 记录连接关闭事件。
     *
     * @param reason 关闭原因
     */
    public void closed(String reason) {
        activeConnections.updateAndGet(value -> Math.max(0, value - 1));
        registry.counter("agent.sse.connections.closed").increment();
        registry.counter("agent.sse.client_disconnect", "reason", reason).increment();
    }

    /**
     * 记录事件发送。
     *
     * @param eventName 事件名称
     */
    public void eventSent(String eventName) {
        registry.counter("agent.sse.events.sent", "event_type", eventName).increment();
    }
}
