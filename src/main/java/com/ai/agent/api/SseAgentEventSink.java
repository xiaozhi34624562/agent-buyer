package com.ai.agent.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SseAgentEventSink implements AgentEventSink, AutoCloseable {
    private final SseEmitter emitter;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ScheduledFuture<?> pingTask;

    public SseAgentEventSink(
            SseEmitter emitter,
            ScheduledExecutorService scheduler,
            MeterRegistry meterRegistry
    ) {
        this.emitter = emitter;
        this.meterRegistry = meterRegistry;
        meterRegistry.counter("agent.sse.connections.opened").increment();
        this.pingTask = scheduler.scheduleAtFixedRate(this::sendPing, 15, 15, TimeUnit.SECONDS);
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(error -> close());
    }

    @Override
    public void onTextDelta(TextDeltaEvent event) {
        send("text_delta", event);
    }

    @Override
    public void onToolUse(ToolUseEvent event) {
        send("tool_use", event);
    }

    @Override
    public void onToolProgress(ToolProgressEvent event) {
        send("tool_progress", event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        send("tool_result", event);
    }

    @Override
    public void onFinal(FinalEvent event) {
        send("final", event);
    }

    @Override
    public void onError(ErrorEvent event) {
        send("error", event);
    }

    private void send(String eventName, Object payload) {
        if (closed.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                if (closed.get()) {
                    return;
                }
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            }
            meterRegistry.counter("agent.sse.events.sent", "event_type", eventName).increment();
        } catch (IOException e) {
            close();
            throw new IllegalStateException("failed to send SSE event", e);
        }
    }

    private void sendPing() {
        try {
            send("ping", Map.of("type", "ping"));
        } catch (RuntimeException ignored) {
            close();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            meterRegistry.counter("agent.sse.connections.closed").increment();
        }
    }
}
