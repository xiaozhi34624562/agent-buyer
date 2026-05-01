package com.ai.agent.api;

import com.ai.agent.tool.ToolStatus;

public record ToolResultEvent(
        String runId,
        String toolUseId,
        ToolStatus status,
        String resultJson,
        String errorJson
) {
}
