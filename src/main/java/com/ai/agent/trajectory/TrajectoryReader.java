package com.ai.agent.trajectory;

import com.ai.agent.llm.LlmMessage;
import com.ai.agent.tool.ToolCall;

import java.util.List;

public interface TrajectoryReader {
    List<LlmMessage> loadMessages(String runId);

    List<ToolCall> findToolCallsByRun(String runId);

    default TrajectorySnapshot loadTrajectorySnapshot(String runId) {
        throw new UnsupportedOperationException("trajectory snapshot query is not implemented");
    }
}
