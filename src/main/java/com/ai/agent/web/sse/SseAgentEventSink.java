package com.ai.agent.web.sse;

import com.ai.agent.security.SensitivePayloadSanitizer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE Agent 事件接收器实现。
 *
 * <p>将 Agent 运行事件转换为 SSE 格式推送给客户端，支持心跳保活和连接状态管理。
 *
 * @author AI Agent
 */
public final class SseAgentEventSink implements AgentEventSink, AutoCloseable {
    private final SseEmitter emitter;
    private final SseMetrics metrics;
    private final SensitivePayloadSanitizer sanitizer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ScheduledFuture<?> pingTask;

    /**
     * 构造 SSE 事件接收器。
     *
     * @param emitter   SSE 发射器
     * @param scheduler 定时任务调度器，用于心跳任务
     * @param metrics   SSE 连接指标收集器
     * @param sanitizer 敏感数据脱敏处理器
     */
    public SseAgentEventSink(
            SseEmitter emitter,
            ScheduledExecutorService scheduler,
            SseMetrics metrics,
            SensitivePayloadSanitizer sanitizer
    ) {
        this.emitter = emitter;
        this.metrics = metrics;
        this.sanitizer = sanitizer;
        metrics.opened();
        this.pingTask = scheduler.scheduleAtFixedRate(this::sendPing, 15, 15, TimeUnit.SECONDS);
        emitter.onCompletion(() -> close("completion"));
        emitter.onTimeout(() -> close("timeout"));
        emitter.onError(error -> close("error"));
    }

    /**
     * 发送文本增量事件。
     *
     * @param event 文本增量事件
     */
    @Override
    public void onTextDelta(TextDeltaEvent event) {
        send("text_delta", event);
    }

    /**
     * 发送工具调用开始事件。
     *
     * @param event 工具调用事件
     */
    @Override
    public void onToolUse(ToolUseEvent event) {
        send("tool_use", event);
    }

    /**
     * 发送工具执行进度事件。
     *
     * @param event 工具进度事件
     */
    @Override
    public void onToolProgress(ToolProgressEvent event) {
        send("tool_progress", event);
    }

    /**
     * 发送工具执行结果事件。
     *
     * @param event 工具结果事件
     */
    @Override
    public void onToolResult(ToolResultEvent event) {
        send("tool_result", event);
    }

    /**
     * 发送运行结束事件。
     *
     * @param event 最终事件
     */
    @Override
    public void onFinal(FinalEvent event) {
        send("final", event);
    }

    /**
     * 发送错误事件。
     *
     * @param event 错误事件
     */
    @Override
    public void onError(ErrorEvent event) {
        send("error", event);
    }

    /**
     * 发送 SSE 事件。
     *
     * @param eventName 事件名称
     * @param payload   事件数据
     */
    private void send(String eventName, Object payload) {
        if (closed.get()) {
            return;
        }
        try {
            synchronized (emitter) {
                if (closed.get()) {
                    return;
                }
                emitter.send(SseEmitter.event().name(eventName).data(sanitizer.sanitizeForSse(payload)));
            }
            metrics.eventSent(eventName);
        } catch (IOException e) {
            close("send_failed");
            throw new IllegalStateException("failed to send SSE event", e);
        }
    }

    /**
     * 发送心跳事件。
     */
    private void sendPing() {
        try {
            send("ping", Map.of("type", "ping"));
        } catch (RuntimeException ignored) {
            close("ping_failed");
        }
    }

    /**
     * 关闭连接（服务器主动关闭）。
     */
    @Override
    public void close() {
        close("server_close");
    }

    /**
     * 关闭连接并记录关闭原因。
     *
     * @param reason 关闭原因
     */
    private void close(String reason) {
        if (closed.compareAndSet(false, true)) {
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            metrics.closed(reason);
        }
    }
}
