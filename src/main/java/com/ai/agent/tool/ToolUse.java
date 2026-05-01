package com.ai.agent.tool;

public record ToolUse(
        String toolUseId,
        String rawToolName,
        String argsJson
) {
}
