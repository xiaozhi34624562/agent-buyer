package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

public record EventDto(
        String eventId,
        String eventType,
        String payloadPreview,
        LocalDateTime createdAt
) {
}
