package com.ai.agent.llm;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public final class TranscriptPairValidator {
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
