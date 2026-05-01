package com.ai.agent.trajectory.dto;

public record MessageToolCallDto(
        String toolUseId,
        String toolName
) {
}
