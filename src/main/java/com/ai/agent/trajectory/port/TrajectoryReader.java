package com.ai.agent.trajectory.port;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.trajectory.model.TrajectorySnapshot;
import java.util.List;

/**
 * 轨迹读取接口。
 * <p>
 * 提供轨迹数据的查询能力，包括消息加载和工具调用查询。
 * </p>
 */
public interface TrajectoryReader {
    /**
     * 加载运行的所有消息。
     *
     * @param runId 运行标识
     * @return 消息列表
     */
    List<LlmMessage> loadMessages(String runId);

    /**
     * 查找运行的所有工具调用。
     *
     * @param runId 运行标识
     * @return 工具调用列表
     */
    List<ToolCall> findToolCallsByRun(String runId);

    /**
     * 加载轨迹快照。
     * <p>
     * 默认实现抛出异常，需要具体存储类支持。
     * </p>
     *
     * @param runId 运行标识
     * @return 轨迹快照
     */
    default TrajectorySnapshot loadTrajectorySnapshot(String runId) {
        throw new UnsupportedOperationException("trajectory snapshot query is not implemented");
    }
}
