package com.ai.agent.web.dto;

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
