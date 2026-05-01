package com.ai.agent.llm;

public record LlmUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
