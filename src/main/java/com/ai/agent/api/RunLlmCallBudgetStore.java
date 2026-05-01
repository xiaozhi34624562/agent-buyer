package com.ai.agent.api;

public interface RunLlmCallBudgetStore {
    Reservation reserveRunCall(String runId, int limit);

    record Reservation(boolean accepted, long used) {
    }
}
