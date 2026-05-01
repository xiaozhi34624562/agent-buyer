package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CompactionDto(
        String compactionId,
        Integer turnNo,
        String attemptId,
        String strategy,
        Integer beforeTokens,
        Integer afterTokens,
        List<String> compactedMessageIds,
        LocalDateTime createdAt
) {
    public CompactionDto {
        compactedMessageIds = compactedMessageIds == null ? List.of() : List.copyOf(compactedMessageIds);
    }
}
