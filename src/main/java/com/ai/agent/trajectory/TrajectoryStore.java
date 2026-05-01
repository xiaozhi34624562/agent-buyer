package com.ai.agent.trajectory;

import com.ai.agent.domain.RunStatus;

public interface TrajectoryStore extends TrajectoryWriter {

    int currentTurn(String runId);

    String findRunUserId(String runId);

    RunStatus findRunStatus(String runId);
}
