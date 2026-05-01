package com.ai.agent.tool.model;

public record ToolTerminal(
        String toolCallId,
        ToolStatus status,
        String resultJson,
        String errorJson,
        CancelReason cancelReason,
        boolean synthetic
) {
    public static ToolTerminal succeeded(String toolCallId, String resultJson) {
        return new ToolTerminal(toolCallId, ToolStatus.SUCCEEDED, resultJson, null, null, false);
    }

    public static ToolTerminal failed(String toolCallId, String errorJson) {
        return new ToolTerminal(toolCallId, ToolStatus.FAILED, null, errorJson, null, false);
    }

    public static ToolTerminal syntheticCancelled(String toolCallId, CancelReason reason, String errorJson) {
        return new ToolTerminal(toolCallId, ToolStatus.CANCELLED, null, errorJson, reason, true);
    }
}
