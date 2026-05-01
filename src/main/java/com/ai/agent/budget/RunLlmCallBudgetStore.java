package com.ai.agent.budget;

public interface RunLlmCallBudgetStore {
    Reservation reserveRunCall(String runId, int limit);

    record Reservation(boolean accepted, long used) {
    }
}
