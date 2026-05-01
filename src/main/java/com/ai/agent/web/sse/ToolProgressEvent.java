package com.ai.agent.web.sse;

public record ToolProgressEvent(String runId, String toolCallId, String stage, String message, Integer percent) {
}
