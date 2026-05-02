package com.ai.agent.tool.model;

/**
 * 取消原因枚举。
 * 定义工具调用被取消的各种原因。
 */
public enum CancelReason {
    /** 用户主动中止 */
    USER_ABORT,
    /** 运行被中止 */
    RUN_ABORTED,
    /** 运行超时 */
    RUN_TIMEOUT,
    /** 工具执行超时 */
    TOOL_TIMEOUT,
    /** 预检查失败 */
    PRECHECK_FAILED,
    /** 执行器拒绝 */
    EXECUTOR_REJECTED,
    /** 租约过期 */
    LEASE_EXPIRED,
    /** 被中断 */
    INTERRUPTED
}
