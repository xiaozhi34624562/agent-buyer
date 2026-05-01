package com.ai.agent.llm.model;

public record LlmUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
