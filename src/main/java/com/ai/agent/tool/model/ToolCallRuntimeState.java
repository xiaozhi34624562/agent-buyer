package com.ai.agent.tool.model;

public record ToolCallRuntimeState(
        ToolCall call,
        ToolStatus status,
        int attempt,
        String leaseToken,
        Long leaseUntil,
        String workerId,
        CancelReason cancelReason,
        String resultJson,
        String errorJson
) {
}
