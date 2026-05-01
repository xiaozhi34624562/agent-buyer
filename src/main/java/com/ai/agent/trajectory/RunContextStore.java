package com.ai.agent.trajectory;

public interface RunContextStore {
    void create(RunContext context);

    RunContext load(String runId);
}
