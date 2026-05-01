package com.ai.agent.api;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.LlmUsage;
import com.ai.agent.llm.ProviderCallException;
import com.ai.agent.llm.ProviderFallbackPolicy;
import com.ai.agent.tool.Tool;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class LlmAttemptService {
    private final LlmProviderAdapterRegistry providerRegistry;
    private final TrajectoryStore trajectoryStore;
    private final ObjectMapper objectMapper;
    private final ProviderFallbackPolicy fallbackPolicy;

    public LlmAttemptService(
            LlmProviderAdapterRegistry providerRegistry,
            TrajectoryStore trajectoryStore,
            ObjectMapper objectMapper
    ) {
        this.providerRegistry = providerRegistry;
        this.trajectoryStore = trajectoryStore;
        this.objectMapper = objectMapper;
        this.fallbackPolicy = new ProviderFallbackPolicy(objectMapper);
    }

    public LlmStreamResult executeAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String providerName,
            String model,
            LlmParams params,
            List<LlmMessage> messages,
            List<Tool> allowedTools,
            AgentEventSink sink
    ) throws Exception {
        return executeSingleAttempt(runId, turnNo, attemptId, providerName, model, params, messages, allowedTools, sink);
    }

    public LlmStreamResult executeAttempt(
            String runId,
            int turnNo,
            String attemptId,
            RunContext runContext,
            String model,
            LlmParams params,
            List<LlmMessage> messages,
            List<Tool> allowedTools,
            AgentEventSink sink
    ) throws Exception {
        try {
            return executeSingleAttempt(
                    runId,
                    turnNo,
                    attemptId,
                    runContext.primaryProvider(),
                    model,
                    params,
                    messages,
                    allowedTools,
                    sink
            );
        } catch (Exception primaryFailure) {
            String fallbackProvider = fallbackPolicy.selectFallbackProvider(runContext, primaryFailure)
                    .orElseThrow(() -> primaryFailure);
            String fallbackAttemptId = Ids.newId("att");
            trajectoryStore.writeAgentEvent(
                    runId,
                    "llm_fallback",
                    fallbackEventJson(attemptId, fallbackAttemptId, runContext.primaryProvider(), fallbackProvider, primaryFailure)
            );
            return executeSingleAttempt(
                    runId,
                    turnNo,
                    fallbackAttemptId,
                    fallbackProvider,
                    null,
                    params,
                    messages,
                    allowedTools,
                    sink
            );
        }
    }

    private LlmStreamResult executeSingleAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String providerName,
            String model,
            LlmParams params,
            List<LlmMessage> messages,
            List<Tool> allowedTools,
            AgentEventSink sink
    ) throws Exception {
        LlmProviderAdapter providerAdapter = providerRegistry.resolve(providerName);
        String actualProviderName = providerAdapter.providerName();
        String actualModel = effectiveModel(model, providerAdapter);
        try {
            LlmStreamResult result = providerAdapter.streamChat(
                    new LlmChatRequest(
                            runId,
                            attemptId,
                            actualModel,
                            params == null ? null : params.temperature(),
                            params == null ? null : params.maxTokens(),
                            messages,
                            allowedTools.stream().map(Tool::schema).toList()
                    ),
                    delta -> sink.onTextDelta(new TextDeltaEvent(runId, attemptId, delta))
            );
            writeSucceededAttempt(runId, turnNo, attemptId, actualProviderName, actualModel, result);
            return result;
        } catch (Exception e) {
            writeFailedAttempt(runId, turnNo, attemptId, actualProviderName, actualModel, e);
            throw e;
        }
    }

    private String effectiveModel(String requestedModel, LlmProviderAdapter providerAdapter) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        return providerAdapter.defaultModel();
    }

    private String fallbackEventJson(
            String primaryAttemptId,
            String fallbackAttemptId,
            String primaryProvider,
            String fallbackProvider,
            Exception failure
    ) {
        String errorType = failure instanceof ProviderCallException providerFailure
                ? providerFailure.type().name()
                : failure.getClass().getSimpleName();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("primaryAttemptId", primaryAttemptId);
            payload.put("fallbackAttemptId", fallbackAttemptId);
            payload.put("primaryProvider", primaryProvider);
            payload.put("fallbackProvider", fallbackProvider);
            payload.put("errorType", errorType);
            if (failure instanceof ProviderCallException providerFailure && providerFailure.statusCode() != null) {
                payload.put("statusCode", providerFailure.statusCode());
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"llm_fallback\"}";
        }
    }

    private void writeSucceededAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String providerName,
            String model,
            LlmStreamResult result
    ) {
        LlmUsage usage = result.usage();
        trajectoryStore.writeLlmAttempt(
                attemptId,
                runId,
                turnNo,
                providerName,
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

    private void writeFailedAttempt(String runId, int turnNo, String attemptId, String providerName, String model, Exception e) {
        trajectoryStore.writeLlmAttempt(
                attemptId,
                runId,
                turnNo,
                providerName,
                model,
                "FAILED",
                FinishReason.ERROR,
                null,
                null,
                null,
                errorJson("provider_failed", e),
                null
        );
    }

    private String errorJson(String type, Exception failure) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            if (failure instanceof ProviderCallException providerFailure) {
                payload.put("providerErrorType", providerFailure.type().name());
                if (providerFailure.statusCode() != null) {
                    payload.put("statusCode", providerFailure.statusCode());
                }
            }
            payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }
}
