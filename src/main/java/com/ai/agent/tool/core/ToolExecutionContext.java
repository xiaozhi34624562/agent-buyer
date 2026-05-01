package com.ai.agent.tool.core;

import com.ai.agent.web.sse.AgentEventSink;

public record ToolExecutionContext(
        String runId,
        String userId,
        AgentEventSink sink
) {
}
