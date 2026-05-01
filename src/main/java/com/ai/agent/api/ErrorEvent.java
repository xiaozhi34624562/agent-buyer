package com.ai.agent.api;

import java.util.Map;

public record ErrorEvent(
        String runId,
        String message,
        String code,
        Map<String, Object> details
) {
    public ErrorEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ErrorEvent(String runId, String message) {
        this(runId, message, null, Map.of());
    }
}
