package com.ai.agent.todo;

import java.time.Instant;

public record TodoStep(
        String stepId,
        String title,
        TodoStatus status,
        String notes,
        Instant updatedAt
) {
}
