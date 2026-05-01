package com.ai.agent.api;

public record ToolUseEvent(String runId, String toolUseId, String toolName, String argsJson) {
}
