package com.ai.agent.todo.model;

import java.time.Instant;

public record TodoStep(
        String stepId,
        String title,
        TodoStatus status,
        String notes,
        Instant updatedAt
) {
}
