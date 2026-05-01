package com.ai.agent.subagent.model;

public record ChildRunRef(
        String parentRunId,
        String childRunId,
        String parentToolCallId,
        String agentType,
        int userTurnNo,
        ChildRunState state,
        ParentLinkStatus parentLinkStatus,
        long reservedAtEpochMs,
        Long releasedAtEpochMs,
        ChildReleaseReason releaseReason
) {
}
