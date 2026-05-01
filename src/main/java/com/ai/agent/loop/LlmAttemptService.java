package com.ai.agent.loop;

import com.ai.agent.budget.LlmCallBudgetExceededException;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.LlmUsage;
import com.ai.agent.llm.provider.LlmCallObserver;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmProviderAdapterRegistry;
import com.ai.agent.llm.provider.ProviderCallException;
import com.ai.agent.llm.provider.ProviderFallbackPolicy;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.trajectory.model.ContextCompactionDraft;
import com.ai.agent.trajectory.model.ContextCompactionRecord;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.ContextCompactionStore;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.ai.agent.web.dto.LlmParams;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.TextDeltaEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class LlmAttemptService {
    private final LlmProviderAdapterRegistry providerRegistry;
    private final TrajectoryStore trajectoryStore;
    private final ContextCompactionStore compactionStore;
    private final ObjectMapper objectMapper;
    private final ProviderFallbackPolicy fallbackPolicy;

    public LlmAttemptService(
            LlmProviderAdapterRegistry providerRegistry,
            TrajectoryStore trajectoryStore,
            ObjectMapper objectMapper,
            ContextCompactionStore compactionStore
    ) {
        this.providerRegistry = providerRegistry;
        this.trajectoryStore = trajectoryStore;
        this.compactionStore = compactionStore;
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
        return executeAttempt(runId, turnNo, attemptId, providerName, model, params, messages, allowedTools, List.of(), sink);
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
            List<ContextCompactionDraft> compactions,
            AgentEventSink sink
    ) throws Exception {
        return executeSingleAttempt(
                runId,
                turnNo,
                attemptId,
                providerName,
                model,
                params,
                messages,
                allowedTools,
                compactions,
                sink,
                LlmCallObserver.NOOP
        );
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
        return executeAttempt(runId, turnNo, attemptId, runContext, model, params, messages, allowedTools, sink, LlmCallObserver.NOOP);
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
            List<ContextCompactionDraft> compactions,
            AgentEventSink sink
    ) throws Exception {
        return executeAttempt(
                runId,
                turnNo,
                attemptId,
                runContext,
                model,
                params,
                messages,
                allowedTools,
                compactions,
                sink,
                LlmCallObserver.NOOP
        );
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
            AgentEventSink sink,
            LlmCallObserver callObserver
    ) throws Exception {
        return executeAttempt(runId, turnNo, attemptId, runContext, model, params, messages, allowedTools, List.of(), sink, callObserver);
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
            List<ContextCompactionDraft> compactions,
            AgentEventSink sink,
            LlmCallObserver callObserver
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
                    compactions,
                    sink,
                    callObserver
            );
        } catch (Exception primaryFailure) {
            String fallbackProvider = fallbackPolicy.selectFallbackProvider(runContext, primaryFailure)
                    .orElseThrow(() -> primaryFailure);
            String fallbackAttemptId = Ids.newId("att");
            try {
                LlmStreamResult fallbackResult = executeSingleAttempt(
                        runId,
                        turnNo,
                        fallbackAttemptId,
                        fallbackProvider,
                        null,
                        params,
                        messages,
                        allowedTools,
                        compactions,
                        sink,
                        callObserver
                );
                writeFallbackEvent(runId, attemptId, fallbackAttemptId, runContext.primaryProvider(), fallbackProvider, primaryFailure);
                return fallbackResult;
            } catch (LlmCallBudgetExceededException budgetExceeded) {
                throw budgetExceeded;
            } catch (Exception fallbackFailure) {
                writeFallbackEvent(runId, attemptId, fallbackAttemptId, runContext.primaryProvider(), fallbackProvider, primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private void writeFallbackEvent(
            String runId,
            String primaryAttemptId,
            String fallbackAttemptId,
            String primaryProvider,
            String fallbackProvider,
            Exception primaryFailure
    ) {
        trajectoryStore.writeAgentEvent(
                runId,
                "llm_fallback",
                fallbackEventJson(primaryAttemptId, fallbackAttemptId, primaryProvider, fallbackProvider, primaryFailure)
        );
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
            List<ContextCompactionDraft> compactions,
            AgentEventSink sink,
            LlmCallObserver callObserver
    ) throws Exception {
        LlmProviderAdapter providerAdapter = providerRegistry.resolve(providerName);
        String actualProviderName = providerAdapter.providerName();
        String actualModel = effectiveModel(model, providerAdapter);
        CompactionRecordingObserver observer = new CompactionRecordingObserver(
                callObserver,
                () -> recordCompactions(runId, turnNo, attemptId, compactions)
        );
        try {
            LlmStreamResult result = providerAdapter.streamChat(
                    new LlmChatRequest(
                            runId,
                            attemptId,
                            actualModel,
                            params == null ? null : params.temperature(),
                            params == null ? null : params.maxTokens(),
                            messages,
                            allowedTools.stream().map(Tool::schema).toList(),
                            observer
                    ),
                    delta -> sink.onTextDelta(new TextDeltaEvent(runId, attemptId, delta))
            );
            writeSucceededAttempt(runId, turnNo, attemptId, actualProviderName, actualModel, result);
            return result;
        } catch (LlmCallBudgetExceededException e) {
            if (observer.providerCallAccepted()) {
                writeBudgetExceededAttempt(runId, turnNo, attemptId, actualProviderName, actualModel, e);
            }
            throw e;
        } catch (Exception e) {
            writeFailedAttempt(runId, turnNo, attemptId, actualProviderName, actualModel, e);
            throw e;
        }
    }

    private void recordCompactions(
            String runId,
            int turnNo,
            String attemptId,
            List<ContextCompactionDraft> compactions
    ) {
        if (compactions == null || compactions.isEmpty()) {
            return;
        }
        for (ContextCompactionDraft compaction : compactions) {
            compactionStore.record(new ContextCompactionRecord(
                    null,
                    runId,
                    turnNo,
                    attemptId,
                    compaction.strategy(),
                    compaction.beforeTokens(),
                    compaction.afterTokens(),
                    compaction.compactedMessageIds(),
                    null
            ));
        }
    }

    private static final class CompactionRecordingObserver implements LlmCallObserver {
        private final LlmCallObserver delegate;
        private final Runnable recordCompactions;
        private boolean recorded;

        private CompactionRecordingObserver(LlmCallObserver delegate, Runnable recordCompactions) {
            this.delegate = delegate == null ? LlmCallObserver.NOOP : delegate;
            this.recordCompactions = recordCompactions;
        }

        @Override
        public void beforeProviderCall() {
            delegate.beforeProviderCall();
            if (!recorded) {
                recorded = true;
                recordCompactions.run();
            }
        }

        private boolean providerCallAccepted() {
            return recorded;
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

    private void writeBudgetExceededAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String providerName,
            String model,
            LlmCallBudgetExceededException e
    ) {
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
                errorJson("llm_budget_exceeded", e),
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
