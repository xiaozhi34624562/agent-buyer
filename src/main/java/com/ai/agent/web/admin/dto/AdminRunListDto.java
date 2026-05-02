package com.ai.agent.web.admin.dto;

import java.time.LocalDateTime;

public record AdminRunListDto(
        String runId,
        String userId,
        String status,
        Integer turnNo,
        String agentType,
        String parentRunId,
        String parentLinkStatus,
        String primaryProvider,
        String fallbackProvider,
        String model,
        Integer maxTurns,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String lastError
) {
}