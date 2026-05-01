package com.ai.agent.tool.model;

public record ToolUse(
        String toolUseId,
        String rawToolName,
        String argsJson
) {
}
