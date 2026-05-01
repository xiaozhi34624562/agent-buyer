package com.ai.agent.llm.model;

public record ToolCallMessage(
        String toolUseId,
        String name,
        String argsJson
) {
}
