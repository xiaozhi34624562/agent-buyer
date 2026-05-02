package com.ai.agent.domain;

/**
 * 运行状态枚举
 *
 * <p>定义Agent运行过程中可能的状态，用于跟踪和展示运行生命周期</p>
 */
public enum RunStatus {
    /** 已创建，初始状态 */
    CREATED,
    /** 运行中 */
    RUNNING,
    /** 已暂停 */
    PAUSED,
    /** 等待用户确认 */
    WAITING_USER_CONFIRMATION,
    /** 执行成功 */
    SUCCEEDED,
    /** 执行失败 */
    FAILED,
    /** 失败后已恢复 */
    FAILED_RECOVERED,
    /** 已取消 */
    CANCELLED,
    /** 执行超时 */
    TIMEOUT
}