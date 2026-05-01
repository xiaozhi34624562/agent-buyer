package com.ai.agent.api;

public interface AgentEventSink {
    void onTextDelta(TextDeltaEvent event);

    void onToolUse(ToolUseEvent event);

    void onToolProgress(ToolProgressEvent event);

    void onToolResult(ToolResultEvent event);

    void onFinal(FinalEvent event);

    void onError(ErrorEvent event);
}
