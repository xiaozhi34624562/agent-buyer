package com.ai.agent.web.sse;

public record ToolUseEvent(String runId, String toolUseId, String toolName, String argsJson) {
}
