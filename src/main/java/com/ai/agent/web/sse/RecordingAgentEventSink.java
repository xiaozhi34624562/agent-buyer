package com.ai.agent.web.sse;

/**
 * 带事件记录功能的 Agent 事件接收器。
 *
 * <p>在将事件转发给下游接收器的同时，将关键事件记录到数据库，用于后续审计和分析。
 *
 * @author AI Agent
 */
public final class RecordingAgentEventSink implements AgentEventSink {
    private final AgentEventSink delegate;
    private final AgentEventRecorder recorder;

    /**
     * 构造带记录功能的事件接收器。
     *
     * @param delegate 下游事件接收器，用于实际推送事件
     * @param recorder 事件记录器，用于持久化事件数据
     */
    public RecordingAgentEventSink(AgentEventSink delegate, AgentEventRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    /**
     * 处理文本增量事件，直接转发不记录。
     *
     * @param event 文本增量事件
     */
    @Override
    public void onTextDelta(TextDeltaEvent event) {
        delegate.onTextDelta(event);
    }

    /**
     * 处理工具调用开始事件，记录并转发。
     *
     * @param event 工具调用事件
     */
    @Override
    public void onToolUse(ToolUseEvent event) {
        recorder.recordEvent(event.runId(), "tool_use", event);
        delegate.onToolUse(event);
    }

    /**
     * 处理工具执行进度事件，记录并转发。
     *
     * @param event 工具进度事件
     */
    @Override
    public void onToolProgress(ToolProgressEvent event) {
        recorder.recordProgress(event);
        delegate.onToolProgress(event);
    }

    /**
     * 处理工具执行结果事件，记录并转发。
     *
     * @param event 工具结果事件
     */
    @Override
    public void onToolResult(ToolResultEvent event) {
        recorder.recordEvent(event.runId(), "tool_result", event);
        delegate.onToolResult(event);
    }

    /**
     * 处理运行结束事件，记录并转发。
     *
     * @param event 最终事件
     */
    @Override
    public void onFinal(FinalEvent event) {
        recorder.recordEvent(event.runId(), "final", event);
        delegate.onFinal(event);
    }

    /**
     * 处理错误事件，记录并转发。
     *
     * @param event 错误事件
     */
    @Override
    public void onError(ErrorEvent event) {
        recorder.recordEvent(event.runId(), "error", event);
        delegate.onError(event);
    }
}
