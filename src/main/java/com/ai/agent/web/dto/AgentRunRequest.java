package com.ai.agent.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Set;

public record AgentRunRequest(
        @NotEmpty List<@Valid UserMessage> messages,
        Set<String> allowedToolNames,
        LlmParams llmParams
) {
}
