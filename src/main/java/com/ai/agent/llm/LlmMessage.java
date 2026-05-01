package com.ai.agent.llm;

import java.util.List;
import java.util.Map;

public record LlmMessage(
        String messageId,
        MessageRole role,
        String content,
        List<ToolCallMessage> toolCalls,
        String toolUseId,
        Map<String, Object> extras
) {
    public LlmMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    public static LlmMessage system(String messageId, String content) {
        return new LlmMessage(messageId, MessageRole.SYSTEM, content, List.of(), null, Map.of());
    }

    public static LlmMessage user(String messageId, String content) {
        return new LlmMessage(messageId, MessageRole.USER, content, List.of(), null, Map.of());
    }

    public static LlmMessage assistant(String messageId, String content, List<ToolCallMessage> toolCalls) {
        return new LlmMessage(messageId, MessageRole.ASSISTANT, content, toolCalls, null, Map.of());
    }

    public static LlmMessage tool(String messageId, String toolUseId, String content) {
        return new LlmMessage(messageId, MessageRole.TOOL, content, List.of(), toolUseId, Map.of());
    }
}
