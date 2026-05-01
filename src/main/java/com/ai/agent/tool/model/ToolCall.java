package com.ai.agent.tool.model;

public record ToolCall(
        String runId,
        String toolCallId,
        long seq,
        String toolUseId,
        String rawToolName,
        String toolName,
        String argsJson,
        boolean isConcurrent,
        boolean idempotent,
        boolean precheckFailed,
        String precheckErrorJson,
        Long timeoutMs
) {
    public ToolCall(
            String runId,
            String toolCallId,
            long seq,
            String toolUseId,
            String rawToolName,
            String toolName,
            String argsJson,
            boolean isConcurrent,
            boolean idempotent,
            boolean precheckFailed,
            String precheckErrorJson
    ) {
        this(
                runId,
                toolCallId,
                seq,
                toolUseId,
                rawToolName,
                toolName,
                argsJson,
                isConcurrent,
                idempotent,
                precheckFailed,
                precheckErrorJson,
                null
        );
    }
}
