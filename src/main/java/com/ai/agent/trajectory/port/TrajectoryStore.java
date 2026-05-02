package com.ai.agent.trajectory.port;

import com.ai.agent.domain.RunStatus;

/**
 * 轨迹存储接口。
 * <p>
 * 继承 TrajectoryWriter，增加查询能力。
 * </p>
 */
public interface TrajectoryStore extends TrajectoryWriter {

    /**
     * 获取当前轮次号。
     *
     * @param runId 运行标识
     * @return 当前轮次号
     */
    int currentTurn(String runId);

    /**
     * 查找运行的用户标识。
     *
     * @param runId 运行标识
     * @return 用户标识
     */
    String findRunUserId(String runId);

    /**
     * 查找运行状态。
     *
     * @param runId 运行标识
     * @return 运行状态
     */
    RunStatus findRunStatus(String runId);
}
