package com.ai.agent.subagent.model;

/**
 * 子运行释放原因枚举。
 * <p>
 * 定义子运行资源释放的各种原因。
 * </p>
 */
public enum ChildReleaseReason {

    /**
     * 成功完成。
     */
    SUCCEEDED,

    /**
     * 执行失败。
     */
    FAILED,

    /**
     * 执行超时。
     */
    TIMEOUT,

    /**
     * 被中断。
     */
    INTERRUPTED,

    /**
     * 父运行失败。
     */
    PARENT_FAILED
}
