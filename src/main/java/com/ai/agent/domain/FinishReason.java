package com.ai.agent.domain;

/**
 * 结束原因枚举
 *
 * <p>定义Agent运行结束的各种原因，用于标识任务终止的具体情况</p>
 */
public enum FinishReason {
    /** 正常停止 */
    STOP,
    /** 需要执行工具调用 */
    TOOL_CALLS,
    /** 达到最大长度限制 */
    LENGTH,
    /** 内容被过滤 */
    CONTENT_FILTER,
    /** 发生错误 */
    ERROR
}