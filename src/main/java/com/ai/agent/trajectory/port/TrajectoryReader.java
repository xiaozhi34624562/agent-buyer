package com.ai.agent.trajectory.port;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.trajectory.model.TrajectorySnapshot;
import java.util.List;

public interface TrajectoryReader {
    List<LlmMessage> loadMessages(String runId);

    List<ToolCall> findToolCallsByRun(String runId);

    default TrajectorySnapshot loadTrajectorySnapshot(String runId) {
        throw new UnsupportedOperationException("trajectory snapshot query is not implemented");
    }
}
