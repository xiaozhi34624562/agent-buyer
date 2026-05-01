package com.ai.agent.web.sse;

public final class RecordingAgentEventSink implements AgentEventSink {
    private final AgentEventSink delegate;
    private final AgentEventRecorder recorder;

    public RecordingAgentEventSink(AgentEventSink delegate, AgentEventRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public void onTextDelta(TextDeltaEvent event) {
        delegate.onTextDelta(event);
    }

    @Override
    public void onToolUse(ToolUseEvent event) {
        recorder.recordEvent(event.runId(), "tool_use", event);
        delegate.onToolUse(event);
    }

    @Override
    public void onToolProgress(ToolProgressEvent event) {
        recorder.recordProgress(event);
        delegate.onToolProgress(event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        recorder.recordEvent(event.runId(), "tool_result", event);
        delegate.onToolResult(event);
    }

    @Override
    public void onFinal(FinalEvent event) {
        recorder.recordEvent(event.runId(), "final", event);
        delegate.onFinal(event);
    }

    @Override
    public void onError(ErrorEvent event) {
        recorder.recordEvent(event.runId(), "error", event);
        delegate.onError(event);
    }
}
