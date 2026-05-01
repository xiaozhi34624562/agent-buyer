package com.ai.agent.subagent;

import java.time.Instant;

public record ReserveChildCommand(
        String parentRunId,
        String childRunId,
        String parentToolCallId,
        String agentType,
        int userTurnNo,
        Instant requestedAt
) {
    public ReserveChildCommand {
        if (parentRunId == null || parentRunId.isBlank()) {
            throw new IllegalArgumentException("parentRunId is required");
        }
        if (childRunId == null || childRunId.isBlank()) {
            throw new IllegalArgumentException("childRunId is required");
        }
        if (parentToolCallId == null || parentToolCallId.isBlank()) {
            throw new IllegalArgumentException("parentToolCallId is required");
        }
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType is required");
        }
        if (userTurnNo <= 0) {
            throw new IllegalArgumentException("userTurnNo must be positive");
        }
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        agentType = agentType.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
