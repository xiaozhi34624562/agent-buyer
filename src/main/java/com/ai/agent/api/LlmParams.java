package com.ai.agent.api;

public record LlmParams(
        String model,
        Double temperature,
        Integer maxTokens,
        Integer maxTurns
) {
    public int effectiveMaxTurns(int configuredMaxTurns) {
        if (maxTurns == null) {
            return configuredMaxTurns;
        }
        return Math.min(maxTurns, configuredMaxTurns);
    }
}
