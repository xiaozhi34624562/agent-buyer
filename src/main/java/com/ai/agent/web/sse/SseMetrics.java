package com.ai.agent.web.sse;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class SseMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeConnections = new AtomicInteger();

    public SseMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("agent.sse.connections.active", activeConnections, AtomicInteger::get)
                .register(registry);
    }

    public void opened() {
        activeConnections.incrementAndGet();
        registry.counter("agent.sse.connections.opened").increment();
    }

    public void closed(String reason) {
        activeConnections.updateAndGet(value -> Math.max(0, value - 1));
        registry.counter("agent.sse.connections.closed").increment();
        registry.counter("agent.sse.client_disconnect", "reason", reason).increment();
    }

    public void eventSent(String eventName) {
        registry.counter("agent.sse.events.sent", "event_type", eventName).increment();
    }
}
