package com.ai.agent.subagent.model;

/**
 * 子运行预留拒绝原因枚举。
 * <p>
 * 定义子运行资源预留被拒绝的各种原因。
 * </p>
 */
public enum ChildReserveRejectReason {

    /**
     * 单次运行最大派生数量超限。
     */
    MAX_SPAWN_PER_RUN,

    /**
     * 单次运行最大并发数量超限。
     */
    MAX_CONCURRENT_PER_RUN,

    /**
     * 单用户轮次派生预算超限。
     */
    SPAWN_BUDGET_PER_USER_TURN
}
