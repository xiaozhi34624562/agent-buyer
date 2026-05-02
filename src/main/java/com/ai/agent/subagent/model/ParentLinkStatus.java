package com.ai.agent.subagent.model;

/**
 * 父子链接状态枚举。
 * <p>
 * 定义父子代理运行之间的链接状态，用于追踪子运行的生命周期。
 * </p>
 */
public enum ParentLinkStatus {

    /**
     * 链接活跃，子运行正在执行。
     */
    LIVE,

    /**
     * 因超时而分离。
     */
    DETACHED_BY_TIMEOUT,

    /**
     * 因中断而分离。
     */
    DETACHED_BY_INTERRUPT,

    /**
     * 因父运行失败而分离。
     */
    DETACHED_BY_PARENT_FAILED
}
