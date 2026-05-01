package com.ai.agent.api;

import jakarta.validation.constraints.NotBlank;

public record UserMessage(
        @NotBlank String role,
        @NotBlank String content
) {
}
