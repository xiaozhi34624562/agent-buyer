package com.ai.agent.subagent.model;

/**
 * 子运行状态枚举。
 * <p>
 * 定义子运行的生命周期状态。
 * </p>
 */
public enum ChildRunState {

    /**
     * 执行中，尚未释放。
     */
    IN_FLIGHT,

    /**
     * 已释放，执行完成或终止。
     */
    RELEASED
}
