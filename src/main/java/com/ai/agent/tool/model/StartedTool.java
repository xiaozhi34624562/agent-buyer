package com.ai.agent.tool.model;

public record StartedTool(
        ToolCall call,
        int attempt,
        String leaseToken,
        long leaseUntil,
        String workerId
) {
}
