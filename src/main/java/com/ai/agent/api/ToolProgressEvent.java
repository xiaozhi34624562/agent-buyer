package com.ai.agent.api;

public record ToolProgressEvent(String runId, String toolCallId, String stage, String message, Integer percent) {
}
