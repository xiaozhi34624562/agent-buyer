package com.ai.agent.web.sse;

/**
 * Agent 事件接收器接口。
 *
 * <p>定义了 Agent 运行过程中各类事件的回调方法，用于将事件推送给订阅者。
 *
 * @author AI Agent
 */
public interface AgentEventSink {

    /**
     * 处理文本增量事件。
     *
     * @param event 文本增量事件
     */
    void onTextDelta(TextDeltaEvent event);

    /**
     * 处理工具调用开始事件。
     *
     * @param event 工具调用事件
     */
    void onToolUse(ToolUseEvent event);

    /**
     * 处理工具执行进度事件。
     *
     * @param event 工具进度事件
     */
    void onToolProgress(ToolProgressEvent event);

    /**
     * 处理工具执行结果事件。
     *
     * @param event 工具结果事件
     */
    void onToolResult(ToolResultEvent event);

    /**
     * 处理运行结束事件。
     *
     * @param event 最终事件
     */
    void onFinal(FinalEvent event);

    /**
     * 处理错误事件。
     *
     * @param event 错误事件
     */
    void onError(ErrorEvent event);
}
