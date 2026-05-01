package com.ai.agent.tool.runtime;

import com.ai.agent.tool.model.ToolCall;

public interface ToolRuntime {
    void onToolUse(String runId, ToolCall call);
}
