package com.ai.agent.tool;

public interface ToolRuntime {
    void onToolUse(String runId, ToolCall call);
}
