package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;
import java.util.List;

public record MessageDto(
        String messageId,
        Long seq,
        String role,
        String contentPreview,
        String toolUseId,
        List<MessageToolCallDto> toolCalls,
        LocalDateTime createdAt
) {
    public MessageDto {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
