package com.ai.agent.llm.transcript;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.llm.model.ToolCallMessage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对话转录验证器。
 * 验证消息列表中工具调用与工具结果的配对完整性，确保每条工具调用都有对应的结果。
 */
@Component
public final class TranscriptPairValidator {
    /**
     * 验证消息列表中工具调用与结果的配对关系。
     *
     * @param messages 待验证的消息列表
     * @throws IllegalStateException 如果发现孤立工具结果、重复工具调用或缺失工具结果
     */
    public void validate(List<LlmMessage> messages) {
        Set<String> openToolUseIds = new LinkedHashSet<>();
        for (LlmMessage message : messages) {
            if (message.role() == MessageRole.ASSISTANT) {
                for (ToolCallMessage toolCall : message.toolCalls()) {
                    if (toolCall.toolUseId() == null || toolCall.toolUseId().isBlank()) {
                        throw new IllegalStateException("assistant tool call missing id");
                    }
                    if (!openToolUseIds.add(toolCall.toolUseId())) {
                        throw new IllegalStateException("duplicate open tool call: " + toolCall.toolUseId());
                    }
                }
            }
            if (message.role() == MessageRole.TOOL) {
                if (!openToolUseIds.remove(message.toolUseId())) {
                    throw new IllegalStateException("orphan tool result: " + message.toolUseId());
                }
            }
        }
        if (!openToolUseIds.isEmpty()) {
            throw new IllegalStateException("missing tool result for: " + String.join(",", openToolUseIds));
        }
    }
}
