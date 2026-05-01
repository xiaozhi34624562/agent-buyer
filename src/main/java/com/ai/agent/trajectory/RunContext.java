package com.ai.agent.trajectory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

public record RunContext(
        String runId,
        List<String> effectiveAllowedTools,
        String model,
        String primaryProvider,
        String fallbackProvider,
        String providerOptions,
        int maxTurns,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public RunContext {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(primaryProvider, "primaryProvider must not be null");
        Objects.requireNonNull(fallbackProvider, "fallbackProvider must not be null");
        Objects.requireNonNull(providerOptions, "providerOptions must not be null");
        effectiveAllowedTools = effectiveAllowedTools == null
                ? List.of()
                : List.copyOf(effectiveAllowedTools);
    }
}
