package com.ai.agent.web.sse;

import com.ai.agent.tool.model.ToolStatus;

public record ToolResultEvent(
        String runId,
        String toolUseId,
        ToolStatus status,
        String resultJson,
        String errorJson
) {
}
