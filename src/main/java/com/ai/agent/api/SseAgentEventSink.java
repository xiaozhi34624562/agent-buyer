package com.ai.agent.api;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SseAgentEventSink implements AgentEventSink, AutoCloseable {
    private final SseEmitter emitter;
    private final SseMetrics metrics;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ScheduledFuture<?> pingTask;

    public SseAgentEventSink(
            SseEmitter emitter,
            ScheduledExecutorService scheduler,
            SseMetrics metrics
    ) {
        this.emitter = emitter;
        this.metrics = metrics;
        metrics.opened();
        this.pingTask = scheduler.scheduleAtFixedRate(this::sendPing, 15, 15, TimeUnit.SECONDS);
        emitter.onCompletion(() -> close("completion"));
        emitter.onTimeout(() -> close("timeout"));
        emitter.onError(error -> close("error"));
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
            metrics.eventSent(eventName);
        } catch (IOException e) {
            close("send_failed");
            throw new IllegalStateException("failed to send SSE event", e);
        }
    }

    private void sendPing() {
        try {
            send("ping", Map.of("type", "ping"));
        } catch (RuntimeException ignored) {
            close("ping_failed");
        }
    }

    @Override
    public void close() {
        close("server_close");
    }

    private void close(String reason) {
        if (closed.compareAndSet(false, true)) {
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            metrics.closed(reason);
        }
    }
}
