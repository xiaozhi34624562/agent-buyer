package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class ProviderSummaryGenerator implements SummaryGenerator {
    private static final String SYSTEM_PROMPT = """
            你是 agent context compaction 专用 summarizer。只输出一个 JSON object，不要 Markdown，不要解释。
            JSON 必须包含且只使用这些顶层字段：
            - summaryText: string，简洁描述被压缩历史中仍对后续任务有用的信息
            - businessFacts: string[]，稳定业务事实，例如订单号、订单状态、用户确认/拒绝、用户约束
            - toolFacts: string[]，工具调用与工具结果中后续必须记住的事实
            - openQuestions: string[]，仍未解决、需要后续继续处理的问题
            - compactedMessageIds: string[]，必须原样返回输入中的 compactedMessageIds，顺序不能改变
            不要编造事实。不能确定的信息不要写入 facts。
            """;

    private final AgentProperties properties;
    private final LlmProviderAdapterRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public ProviderSummaryGenerator(
            AgentProperties properties,
            LlmProviderAdapterRegistry providerRegistry,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(String runId, List<LlmMessage> messagesToCompact) {
        LlmProviderAdapter provider = providerRegistry.resolve(properties.getLlm().getProvider());
        LlmStreamResult result = provider.streamChat(
                new LlmChatRequest(
                        runId,
                        Ids.newId("summary_att"),
                        provider.defaultModel(),
                        0.0,
                        properties.getContext().getSummaryMaxTokens(),
                        List.of(
                                LlmMessage.system("summary-system", SYSTEM_PROMPT),
                                LlmMessage.user("summary-input", summaryInput(messagesToCompact))
                        ),
                        List.of()
                ),
                ignored -> {
                }
        );
        if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
            throw new IllegalStateException("summary provider returned tool calls");
        }
        String content = stripJsonFence(result.content());
        if (content.isBlank()) {
            throw new IllegalStateException("summary provider returned blank content");
        }
        return content;
    }

    private String summaryInput(List<LlmMessage> messagesToCompact) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("compactedMessageIds", messagesToCompact.stream().map(LlmMessage::messageId).toList());
        input.put("messages", messagesToCompact.stream().map(this::messagePayload).toList());
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize summary input", e);
        }
    }

    private Map<String, Object> messagePayload(LlmMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.messageId());
        payload.put("role", message.role().name());
        payload.put("content", message.content());
        if (message.toolUseId() != null) {
            payload.put("toolUseId", message.toolUseId());
        }
        if (!message.toolCalls().isEmpty()) {
            payload.put("toolCalls", message.toolCalls().stream().map(this::toolCallPayload).toList());
        }
        return payload;
    }

    private Map<String, Object> toolCallPayload(ToolCallMessage toolCall) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolUseId", toolCall.toolUseId());
        payload.put("name", toolCall.name());
        payload.put("argsJson", toolCall.argsJson());
        return payload;
    }

    private String stripJsonFence(String content) {
        if (content == null) {
            return "";
        }
        String stripped = content.strip();
        if (stripped.startsWith("```json")) {
            stripped = stripped.substring("```json".length()).strip();
        } else if (stripped.startsWith("```")) {
            stripped = stripped.substring("```".length()).strip();
        }
        if (stripped.endsWith("```")) {
            stripped = stripped.substring(0, stripped.length() - 3).strip();
        }
        return stripped;
    }
}
