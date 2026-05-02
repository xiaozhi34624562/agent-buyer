package com.ai.agent.web.admin.dto;

import java.util.Map;

public record AdminRuntimeStateDto(
        String runId,
        boolean activeRun,
        Map<String, Object> entries
) {
}