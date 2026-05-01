package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

public record ToolResultDto(
        String resultId,
        String toolCallId,
        String toolUseId,
        String status,
        Boolean synthetic,
        String cancelReason,
        String preview,
        LocalDateTime createdAt
) {
}
