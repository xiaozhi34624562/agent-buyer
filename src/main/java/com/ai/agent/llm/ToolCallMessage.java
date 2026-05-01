package com.ai.agent.llm;

public record ToolCallMessage(
        String toolUseId,
        String name,
        String argsJson
) {
}
