package com.ai.agent.web.sse;

public record TextDeltaEvent(String runId, String attemptId, String delta) {
}
