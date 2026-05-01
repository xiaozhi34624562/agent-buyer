package com.ai.agent.api;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

public final class SseAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter;

    public SseAgentEventSink(SseEmitter emitter) {
        this.emitter = emitter;
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
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IOException e) {
            throw new IllegalStateException("failed to send SSE event", e);
        }
    }
}
