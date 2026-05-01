package com.ai.agent.tool;

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
