package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

public record LlmAttemptDto(
        String attemptId,
        Integer turnNo,
        String provider,
        String model,
        String status,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String errorPreview,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
