package com.ai.agent.tool;

public record StartedTool(
        ToolCall call,
        int attempt,
        String leaseToken,
        long leaseUntil,
        String workerId
) {
}
