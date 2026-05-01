package com.ai.agent.tool.core;

import java.time.Duration;
import java.util.List;

public record ToolSchema(
        String name,
        String description,
        String parametersJsonSchema,
        boolean isConcurrent,
        boolean idempotent,
        Duration timeout,
        int maxResultBytes,
        List<String> sensitiveFields
) {
    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description is required");
        }
        if (parametersJsonSchema == null || parametersJsonSchema.isBlank()) {
            throw new IllegalArgumentException("parametersJsonSchema is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("positive timeout is required");
        }
        if (maxResultBytes <= 0) {
            throw new IllegalArgumentException("positive maxResultBytes is required");
        }
        sensitiveFields = sensitiveFields == null ? List.of() : List.copyOf(sensitiveFields);
    }
}
