package com.ai.agent.tool;

public record ToolValidation(
        boolean accepted,
        String normalizedArgsJson,
        String errorJson
) {
    public static ToolValidation accepted(String normalizedArgsJson) {
        return new ToolValidation(true, normalizedArgsJson, null);
    }

    public static ToolValidation rejected(String errorJson) {
        return new ToolValidation(false, null, errorJson);
    }
}
