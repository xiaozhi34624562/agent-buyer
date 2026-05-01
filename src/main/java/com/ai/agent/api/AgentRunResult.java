package com.ai.agent.api;

import com.ai.agent.domain.RunStatus;

public record AgentRunResult(
        String runId,
        RunStatus status,
        String finalText
) {
}
