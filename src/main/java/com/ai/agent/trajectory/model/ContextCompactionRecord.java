package com.ai.agent.trajectory.model;

import java.time.LocalDateTime;
import java.util.List;

public record ContextCompactionRecord(
        String compactionId,
        String runId,
        Integer turnNo,
        String attemptId,
        String strategy,
        Integer beforeTokens,
        Integer afterTokens,
        List<String> compactedMessageIds,
        LocalDateTime createdAt
) {
    public ContextCompactionRecord {
        compactedMessageIds = compactedMessageIds == null ? List.of() : List.copyOf(compactedMessageIds);
    }
}
