package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

public record RunDto(
        String runId,
        String status,
        Integer turnNo,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String lastErrorPreview
) {
}
