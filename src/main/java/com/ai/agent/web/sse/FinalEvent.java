package com.ai.agent.web.sse;

import com.ai.agent.domain.RunStatus;

public record FinalEvent(
        String runId,
        String finalText,
        RunStatus status,
        String nextActionRequired
) {
}
