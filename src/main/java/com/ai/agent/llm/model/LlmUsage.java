package com.ai.agent.llm.model;

/**
 * LLM使用量统计。
 * <p>
 * 记录LLM调用过程中的token使用情况，包括提示token、补全token和总token数。
 * </p>
 */
public record LlmUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {
}
