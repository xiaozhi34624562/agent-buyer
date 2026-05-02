package com.ai.agent.llm.summary;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.llm.model.ToolCallMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性摘要生成器。
 * 使用确定性规则生成对话摘要，不依赖LLM，用于快速压缩历史消息。
 */
public final class DeterministicSummaryGenerator implements SummaryGenerator {
    private static final int FACT_PREVIEW_CHARS = 240;

    private final ObjectMapper objectMapper;

    public DeterministicSummaryGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成确定性摘要。
     *
     * @param context        摘要生成上下文
     * @param messagesToCompact 待压缩的消息列表
     * @return JSON格式的摘要内容
     */
    @Override
    public String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact) {
        List<String> compactedMessageIds = messagesToCompact.stream()
                .map(LlmMessage::messageId)
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summaryText", "Deterministic compact summary covering "
                + messagesToCompact.size() + " message(s): " + String.join(", ", compactedMessageIds));
        summary.put("businessFacts", businessFacts(messagesToCompact));
        summary.put("toolFacts", toolFacts(messagesToCompact));
        summary.put("openQuestions", openQuestions(messagesToCompact));
        summary.put("compactedMessageIds", compactedMessageIds);
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize compact summary", e);
        }
    }

    private List<String> businessFacts(List<LlmMessage> messages) {
        List<String> facts = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message.role() == MessageRole.TOOL || message.content() == null || message.content().isBlank()) {
                continue;
            }
            facts.add(message.role().name().toLowerCase() + " " + message.messageId() + ": "
                    + preview(message.content()));
        }
        return List.copyOf(facts);
    }

    private List<String> toolFacts(List<LlmMessage> messages) {
        List<String> facts = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message.role() == MessageRole.ASSISTANT && !message.toolCalls().isEmpty()) {
                for (ToolCallMessage toolCall : message.toolCalls()) {
                    facts.add("tool call " + toolCall.toolUseId() + " -> " + toolCall.name());
                }
            }
            if (message.role() == MessageRole.TOOL) {
                facts.add("tool result " + message.toolUseId() + ": " + preview(message.content()));
            }
        }
        return List.copyOf(facts);
    }

    private List<String> openQuestions(List<LlmMessage> messages) {
        List<String> questions = new ArrayList<>();
        for (LlmMessage message : messages) {
            if (message.role() == MessageRole.USER && message.content() != null && message.content().trim().endsWith("?")) {
                questions.add(preview(message.content()));
            }
        }
        return List.copyOf(questions);
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= FACT_PREVIEW_CHARS) {
            return normalized;
        }
        return normalized.substring(0, FACT_PREVIEW_CHARS);
    }
}
