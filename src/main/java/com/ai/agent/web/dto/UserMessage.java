package com.ai.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UserMessage(
        @NotBlank String role,
        @NotBlank String content
) {
}
