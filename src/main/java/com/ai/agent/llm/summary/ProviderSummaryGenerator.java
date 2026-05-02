package com.ai.agent.llm.summary;

import com.ai.agent.budget.LlmCallBudgetExceededException;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.LlmUsage;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.llm.provider.LlmCallObserver;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmProviderAdapterRegistry;
import com.ai.agent.llm.provider.ProviderCallException;
import com.ai.agent.llm.provider.ProviderErrorType;
import com.ai.agent.llm.provider.ProviderFallbackPolicy;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 基于LLM提供者的摘要生成器。
 * 使用大语言模型生成对话摘要，支持主备提供者切换。
 */
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
    private final TrajectoryStore trajectoryStore;
    private final ObjectMapper objectMapper;
    private final ProviderFallbackPolicy fallbackPolicy;

    public ProviderSummaryGenerator(
            AgentProperties properties,
            LlmProviderAdapterRegistry providerRegistry,
            TrajectoryStore trajectoryStore,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.trajectoryStore = trajectoryStore;
        this.objectMapper = objectMapper;
        this.fallbackPolicy = new ProviderFallbackPolicy(objectMapper);
    }

    @Override
    public String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact) {
        String attemptId = Ids.newId("summary_att");
        String primaryProviderName = primaryProviderName(context);
        try {
            return executeSummaryAttempt(context, messagesToCompact, attemptId, primaryProviderName);
        } catch (RuntimeException primaryFailure) {
            String fallbackProvider = fallbackProvider(context, primaryFailure);
            if (fallbackProvider == null) {
                throw primaryFailure;
            }
            String fallbackAttemptId = Ids.newId("summary_att");
            try {
                String summary = executeSummaryAttempt(context, messagesToCompact, fallbackAttemptId, fallbackProvider);
                writeFallbackEvent(context.runId(), attemptId, fallbackAttemptId, primaryProviderName, fallbackProvider, primaryFailure);
                return summary;
            } catch (LlmCallBudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            } catch (RuntimeException fallbackFailure) {
                writeFallbackEvent(context.runId(), attemptId, fallbackAttemptId, primaryProviderName, fallbackProvider, primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private String executeSummaryAttempt(
            SummaryGenerationContext context,
            List<LlmMessage> messagesToCompact,
            String attemptId,
            String providerName
    ) {
        LlmProviderAdapter provider = providerRegistry.resolve(providerName);
        String actualProviderName = provider.providerName();
        String model = provider.defaultModel();
        SummaryCallObserver observer = new SummaryCallObserver(context.callObserver());
        try {
            LlmStreamResult result = provider.streamChat(
                    new LlmChatRequest(
                            context.runId(),
                            attemptId,
                            model,
                            0.0,
                            properties.getContext().getSummaryMaxTokens(),
                            List.of(
                                    LlmMessage.system("summary-system", SYSTEM_PROMPT),
                                    LlmMessage.user("summary-input", summaryInput(messagesToCompact))
                            ),
                            List.of(),
                            observer
                    ),
                    ignored -> {
                    }
            );
            if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
                throw new IllegalStateException("summary provider returned tool calls");
            }
            if (result.finishReason() == FinishReason.LENGTH) {
                throw new ProviderCallException(
                        ProviderErrorType.RETRYABLE_PRE_STREAM,
                        "summary provider reached max tokens before producing complete JSON"
                );
            }
            String content = stripJsonFence(result.content());
            if (content.isBlank()) {
                throw new IllegalStateException("summary provider returned blank content");
            }
            writeAttempt(context, attemptId, actualProviderName, model, "SUCCEEDED", result.finishReason(), result, null);
            return content;
        } catch (RuntimeException e) {
            if (observer.providerCallAccepted()) {
                writeAttempt(context, attemptId, actualProviderName, model, "FAILED", FinishReason.ERROR, null, errorJson(e));
            }
            throw e;
        }
    }

    private String primaryProviderName(SummaryGenerationContext context) {
        RunContext runContext = context.runContext();
        if (runContext != null && runContext.primaryProvider() != null && !runContext.primaryProvider().isBlank()) {
            return runContext.primaryProvider();
        }
        return properties.getLlm().getProvider();
    }

    private String fallbackProvider(SummaryGenerationContext context, RuntimeException failure) {
        RunContext runContext = context.runContext();
        if (runContext == null) {
            return null;
        }
        return fallbackPolicy.selectFallbackProvider(runContext, failure).orElse(null);
    }

    private void writeFallbackEvent(
            String runId,
            String primaryAttemptId,
            String fallbackAttemptId,
            String primaryProvider,
            String fallbackProvider,
            RuntimeException primaryFailure
    ) {
        trajectoryStore.writeAgentEvent(
                runId,
                "llm_fallback",
                fallbackEventJson(primaryAttemptId, fallbackAttemptId, primaryProvider, fallbackProvider, primaryFailure)
        );
    }

    private String fallbackEventJson(
            String primaryAttemptId,
            String fallbackAttemptId,
            String primaryProvider,
            String fallbackProvider,
            RuntimeException failure
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("primaryAttemptId", primaryAttemptId);
            payload.put("fallbackAttemptId", fallbackAttemptId);
            payload.put("primaryProvider", primaryProvider);
            payload.put("fallbackProvider", fallbackProvider);
            payload.put("errorType", failure instanceof ProviderCallException providerFailure
                    ? providerFailure.type().name()
                    : failure.getClass().getSimpleName());
            if (failure instanceof ProviderCallException providerFailure && providerFailure.statusCode() != null) {
                payload.put("statusCode", providerFailure.statusCode());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"llm_fallback\"}";
        }
    }

    private void writeAttempt(
            SummaryGenerationContext context,
            String attemptId,
            String provider,
            String model,
            String status,
            FinishReason finishReason,
            LlmStreamResult result,
            String errorJson
    ) {
        LlmUsage usage = result == null ? null : result.usage();
        trajectoryStore.writeLlmAttempt(
                attemptId,
                context.runId(),
                context.turnNo(),
                provider,
                model,
                status,
                finishReason,
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                errorJson,
                result == null ? null : result.rawDiagnosticJson()
        );
    }

    private String errorJson(Exception failure) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "summary_provider_failed");
            payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"summary_provider_failed\"}";
        }
    }

    private static final class SummaryCallObserver implements LlmCallObserver {
        private final LlmCallObserver delegate;
        private boolean accepted;

        private SummaryCallObserver(LlmCallObserver delegate) {
            this.delegate = delegate == null ? LlmCallObserver.NOOP : delegate;
        }

        @Override
        public void beforeProviderCall() {
            delegate.beforeProviderCall();
            accepted = true;
        }

        private boolean providerCallAccepted() {
            return accepted;
        }
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
        String jsonObject = firstCompleteJsonObject(stripped);
        return jsonObject == null ? stripped : jsonObject;
    }

    private String firstCompleteJsonObject(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1).strip();
                }
            }
        }
        return null;
    }
}
