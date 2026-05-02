package com.ai.agent.llm.provider;

/**
 * 提供者错误类型枚举。
 * 定义LLM调用过程中可能发生的错误类别。
 */
public enum ProviderErrorType {
    /** 流开始前的可重试错误，如网络超时、连接失败 */
    RETRYABLE_PRE_STREAM,
    /** 不可重试的错误，如认证失败、模型不存在 */
    NON_RETRYABLE,
    /** 流已开始后的错误，此时不应重试 */
    STREAM_STARTED
}
