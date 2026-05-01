package com.ai.agent.tool;

import com.ai.agent.api.AgentEventSink;

public record ToolExecutionContext(
        String runId,
        String userId,
        AgentEventSink sink
) {
}
