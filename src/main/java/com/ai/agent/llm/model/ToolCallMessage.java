package com.ai.agent.llm.model;

/**
 * 工具调用消息。
 * <p>
 * 封装LLM发起的工具调用信息，包括工具调用ID、工具名称和参数JSON。
 * </p>
 */
public record ToolCallMessage(
        String toolUseId,
        String name,
        String argsJson
) {
}
