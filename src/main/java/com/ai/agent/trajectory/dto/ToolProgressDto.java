package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

public record ToolProgressDto(
        String progressId,
        String toolCallId,
        String stage,
        String messagePreview,
        Integer percent,
        LocalDateTime createdAt
) {
}
