package com.ai.agent.llm.model;

import java.util.List;
import java.util.Map;

/**
 * LLM消息。
 * <p>
 * 封装与LLM交互的单条消息，包括消息ID、角色、内容、工具调用和额外信息。
 * </p>
 */
public record LlmMessage(
        String messageId,
        MessageRole role,
        String content,
        List<ToolCallMessage> toolCalls,
        String toolUseId,
        Map<String, Object> extras
) {
    /**
     * 紧凑构造函数，确保工具调用列表和额外信息非空且不可变。
     */
    public LlmMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /**
     * 创建系统消息。
     *
     * @param messageId 消息ID
     * @param content   消息内容
     * @return 系统消息实例
     */
    public static LlmMessage system(String messageId, String content) {
        return new LlmMessage(messageId, MessageRole.SYSTEM, content, List.of(), null, Map.of());
    }

    /**
     * 创建用户消息。
     *
     * @param messageId 消息ID
     * @param content   消息内容
     * @return 用户消息实例
     */
    public static LlmMessage user(String messageId, String content) {
        return new LlmMessage(messageId, MessageRole.USER, content, List.of(), null, Map.of());
    }

    /**
     * 创建助手消息。
     *
     * @param messageId  消息ID
     * @param content    消息内容
     * @param toolCalls  工具调用列表
     * @return 助手消息实例
     */
    public static LlmMessage assistant(String messageId, String content, List<ToolCallMessage> toolCalls) {
        return new LlmMessage(messageId, MessageRole.ASSISTANT, content, toolCalls, null, Map.of());
    }

    /**
     * 创建工具结果消息。
     *
     * @param messageId  消息ID
     * @param toolUseId  工具调用ID
     * @param content    工具返回内容
     * @return 工具结果消息实例
     */
    public static LlmMessage tool(String messageId, String toolUseId, String content) {
        return new LlmMessage(messageId, MessageRole.TOOL, content, List.of(), toolUseId, Map.of());
    }
}
