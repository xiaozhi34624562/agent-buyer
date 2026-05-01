package com.ai.agent.tool;

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
        String precheckErrorJson
) {
}
