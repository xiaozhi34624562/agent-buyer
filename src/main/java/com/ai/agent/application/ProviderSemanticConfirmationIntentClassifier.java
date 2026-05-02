package com.ai.agent.application;

import com.ai.agent.application.HumanIntentResolver.ConfirmationDecision;
import com.ai.agent.application.HumanIntentResolver.ConfirmationIntent;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.LlmUsage;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmProviderAdapterRegistry;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基于LLM提供者的语义确认意图分类器。
 * 使用大语言模型分析用户消息，判断用户是否确认执行待处理的高风险操作。
 * 支持主备提供者切换。
 */
@Service
public final class ProviderSemanticConfirmationIntentClassifier implements SemanticConfirmationIntentClassifier {
    private static final Logger log = LoggerFactory.getLogger(ProviderSemanticConfirmationIntentClassifier.class);
    private static final String SYSTEM_PROMPT = """
            You are a strict confirmation-intent classifier for a pending high-risk business action.

            Classify the user's latest message only:
            - CONFIRM: the user clearly authorizes continuing the pending action.
            - REJECT: the user clearly cancels, rejects, or asks not to continue.
            - CLARIFY: anything uncertain, conditional, informational, or asking a question.

            Return only one JSON object with this exact shape:
            {"intent":"CONFIRM|REJECT|CLARIFY","confidence":0.0,"question":"short clarification question when intent is CLARIFY"}

            Safety rules:
            - If the message is ambiguous, choose CLARIFY.
            - If the message asks a question, choose CLARIFY.
            - Never infer confirmation from politeness alone.
            """;

    private static final String DEFAULT_QUESTION = "请明确回复确认继续执行，或回复放弃本次操作。";

    private final LlmProviderAdapterRegistry providerRegistry;
    private final TrajectoryStore trajectoryStore;
    private final PendingConfirmToolStore pendingConfirmToolStore;
    private final ObjectMapper objectMapper;

    public ProviderSemanticConfirmationIntentClassifier(
            LlmProviderAdapterRegistry providerRegistry,
            TrajectoryStore trajectoryStore,
            PendingConfirmToolStore pendingConfirmToolStore,
            ObjectMapper objectMapper
    ) {
        this.providerRegistry = providerRegistry;
        this.trajectoryStore = trajectoryStore;
        this.pendingConfirmToolStore = pendingConfirmToolStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public ConfirmationDecision classify(String runId, String userId, RunContext runContext, String userMessage) {
        int turnNo = trajectoryStore.nextTurn(runId);
        RuntimeException primaryFailure = null;
        try {
            return classifyWithProvider(runId, userId, runContext, turnNo, runContext.primaryProvider(), runContext.model(), userMessage);
        } catch (RuntimeException e) {
            primaryFailure = e;
            log.warn("primary confirmation classifier failed provider={} runId={} error={}",
                    runContext.primaryProvider(), runId, e.getMessage());
        }

        if (runContext.fallbackProvider() != null
                && !runContext.fallbackProvider().isBlank()
                && !runContext.fallbackProvider().equals(runContext.primaryProvider())) {
            try {
                return classifyWithProvider(runId, userId, runContext, turnNo, runContext.fallbackProvider(), null, userMessage);
            } catch (RuntimeException e) {
                log.warn("fallback confirmation classifier failed provider={} runId={} error={}",
                        runContext.fallbackProvider(), runId, e.getMessage());
                writeEvent(runId, Map.of(
                        "intent", "CLARIFY",
                        "source", "llm_error",
                        "primaryError", safeMessage(primaryFailure),
                        "fallbackError", safeMessage(e)
                ));
                return ConfirmationDecision.clarify(DEFAULT_QUESTION, "llm_error");
            }
        }

        writeEvent(runId, Map.of(
                "intent", "CLARIFY",
                "source", "llm_error",
                "primaryError", safeMessage(primaryFailure)
        ));
        return ConfirmationDecision.clarify(DEFAULT_QUESTION, "llm_error");
    }

    private ConfirmationDecision classifyWithProvider(
            String runId,
            String userId,
            RunContext runContext,
            int turnNo,
            String providerName,
            String requestedModel,
            String userMessage
    ) {
        String attemptId = Ids.newId("hitl_att");
        String actualProvider = providerName;
        String model = requestedModel;
        try {
            LlmProviderAdapter provider = providerRegistry.resolve(providerName);
            actualProvider = provider.providerName();
            model = requestedModel == null || requestedModel.isBlank() ? provider.defaultModel() : requestedModel;
            LlmStreamResult result = provider.streamChat(
                    new LlmChatRequest(
                            runId,
                            attemptId,
                            model,
                            0.0d,
                            256,
                            List.of(
                                    LlmMessage.system(Ids.newId("msg"), SYSTEM_PROMPT),
                                    LlmMessage.user(Ids.newId("msg"), userPrompt(userId, runContext, userMessage))
                            ),
                            List.of()
                    ),
                    ignoredDelta -> {
                    }
            );
            writeSucceededAttempt(runId, turnNo, attemptId, actualProvider, model, result);
            ConfirmationDecision decision = parseDecision(result.content(), provider.providerName());
            writeEvent(runId, Map.of(
                    "intent", decision.intent().name(),
                    "confidence", decision.confidence(),
                    "source", decision.source(),
                    "provider", provider.providerName()
            ));
            return decision;
        } catch (RuntimeException e) {
            writeFailedAttempt(runId, turnNo, attemptId, actualProvider, model, e);
            throw e;
        }
    }

    private String userPrompt(String userId, RunContext runContext, String userMessage) {
        return """
                userId: %s
                runId: %s
                pendingAction: A previous tool dry-run is waiting for human confirmation.
                %s
                latestUserMessage:
                %s
                """.formatted(
                userId,
                runContext.runId(),
                pendingActionContext(runContext.runId()),
                userMessage == null ? "" : userMessage
        ).trim();
    }

    private String pendingActionContext(String runId) {
        if (pendingConfirmToolStore == null) {
            return "pendingContext: unavailable";
        }
        try {
            PendingConfirmToolStore.PendingConfirmTool pending = pendingConfirmToolStore.load(runId);
            if (pending == null) {
                return "pendingContext: unavailable";
            }
            return """
                    pendingToolName: %s
                    pendingSummary: %s
                    pendingArgsJson: %s
                    """.formatted(
                    pending.toolName(),
                    pending.summary() == null ? "" : pending.summary(),
                    sanitizePendingArgs(pending.argsJson())
            ).trim();
        } catch (RuntimeException e) {
            log.warn("failed to load pending confirmation context runId={} error={}", runId, e.getMessage());
            return "pendingContext: unavailable";
        }
    }

    private String sanitizePendingArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "{}";
        }
        try {
            JsonNode root = objectMapper.readTree(argsJson);
            if (root instanceof ObjectNode objectNode) {
                objectNode.remove("confirmToken");
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ConfirmationDecision parseDecision(String content, String source) {
        String json = extractJsonObject(content);
        if (json == null) {
            return ConfirmationDecision.clarify(DEFAULT_QUESTION, source);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            ConfirmationIntent intent = parseIntent(root.path("intent").asText(""));
            double confidence = root.path("confidence").isNumber() ? root.path("confidence").asDouble() : 0.0d;
            String question = root.path("question").asText(DEFAULT_QUESTION);
            if (intent == ConfirmationIntent.CLARIFY) {
                return ConfirmationDecision.clarify(question == null || question.isBlank() ? DEFAULT_QUESTION : question, source);
            }
            return new ConfirmationDecision(intent, confidence, null, source);
        } catch (Exception e) {
            return ConfirmationDecision.clarify(DEFAULT_QUESTION, source);
        }
    }

    private ConfirmationIntent parseIntent(String value) {
        try {
            return ConfirmationIntent.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return ConfirmationIntent.CLARIFY;
        }
    }

    private String extractJsonObject(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }

    private void writeEvent(String runId, Map<String, ?> payload) {
        try {
            trajectoryStore.writeAgentEvent(
                    runId,
                    "confirmation_intent_llm",
                    objectMapper.writeValueAsString(new LinkedHashMap<>(payload))
            );
        } catch (Exception e) {
            log.warn("failed to write confirmation intent event runId={} error={}", runId, e.getMessage());
        }
    }

    private void writeSucceededAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String provider,
            String model,
            LlmStreamResult result
    ) {
        LlmUsage usage = result.usage();
        writeAttempt(
                runId,
                turnNo,
                attemptId,
                provider,
                model,
                "SUCCEEDED",
                result.finishReason(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                null,
                result.rawDiagnosticJson()
        );
    }

    private void writeFailedAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String provider,
            String model,
            RuntimeException failure
    ) {
        writeAttempt(
                runId,
                turnNo,
                attemptId,
                provider,
                model,
                "FAILED",
                FinishReason.ERROR,
                null,
                null,
                null,
                errorJson("confirmation_classifier_failed", failure),
                null
        );
    }

    private void writeAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String provider,
            String model,
            String status,
            FinishReason finishReason,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String errorJson,
            String rawDiagnosticJson
    ) {
        try {
            trajectoryStore.writeLlmAttempt(
                    attemptId,
                    runId,
                    turnNo,
                    provider,
                    model,
                    status,
                    finishReason,
                    promptTokens,
                    completionTokens,
                    totalTokens,
                    errorJson,
                    rawDiagnosticJson
            );
        } catch (Exception e) {
            log.warn("failed to write confirmation classifier attempt runId={} attemptId={} error={}",
                    runId, attemptId, e.getMessage());
        }
    }

    private String errorJson(String type, RuntimeException failure) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("message", safeMessage(failure));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }

    private String safeMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null) {
            return "";
        }
        return exception.getMessage();
    }
}
