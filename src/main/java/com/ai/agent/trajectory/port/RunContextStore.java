package com.ai.agent.trajectory.port;

import com.ai.agent.trajectory.model.RunContext;

public interface RunContextStore {
    void create(RunContext context);

    RunContext load(String runId);
}
