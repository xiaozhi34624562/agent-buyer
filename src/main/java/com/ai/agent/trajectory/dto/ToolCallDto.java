package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

public record ToolCallDto(
        String toolCallId,
        String messageId,
        Long seq,
        String toolUseId,
        String toolName,
        Boolean concurrent,
        Boolean idempotent,
        Boolean precheckFailed,
        String precheckErrorPreview,
        LocalDateTime createdAt
) {
}
