package com.ai.agent.web.dto;

import com.ai.agent.domain.RunStatus;

public record AgentRunResult(
        String runId,
        RunStatus status,
        String finalText
) {
}
