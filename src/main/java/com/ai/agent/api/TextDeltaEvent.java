package com.ai.agent.api;

public record TextDeltaEvent(String runId, String attemptId, String delta) {
}
