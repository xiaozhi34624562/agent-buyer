package com.ai.agent.api;

import com.ai.agent.domain.RunStatus;

public record FinalEvent(
        String runId,
        String finalText,
        RunStatus status,
        String nextActionRequired
) {
}
