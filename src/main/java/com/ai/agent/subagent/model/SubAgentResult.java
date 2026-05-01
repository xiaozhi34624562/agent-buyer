package com.ai.agent.subagent.model;

import com.ai.agent.domain.RunStatus;

public record SubAgentResult(
        String childRunId,
        RunStatus status,
        String summary,
        boolean partial
) {
}
