package com.ai.agent.tool.model;

/**
 * 工具状态枚举。
 * 定义工具调用在执行过程中的状态。
 */
public enum ToolStatus {
    /** 等待执行 */
    WAITING,
    /** 正在执行 */
    RUNNING,
    /** 执行成功 */
    SUCCEEDED,
    /** 执行失败 */
    FAILED,
    /** 已取消 */
    CANCELLED
}
