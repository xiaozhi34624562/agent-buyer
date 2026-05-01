package com.ai.agent.trajectory.port;

import com.ai.agent.trajectory.model.ContextCompactionRecord;

public interface ContextCompactionStore {
    String record(ContextCompactionRecord record);
}
