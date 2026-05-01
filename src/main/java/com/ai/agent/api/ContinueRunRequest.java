package com.ai.agent.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record ContinueRunRequest(
        @NotNull @Valid UserMessage message
) {
}
