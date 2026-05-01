package com.ai.agent.api;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.LlmUsage;
import com.ai.agent.tool.Tool;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class LlmAttemptService {
    private final LlmProviderAdapter providerAdapter;
    private final TrajectoryStore trajectoryStore;
    private final ObjectMapper objectMapper;

    public LlmAttemptService(
            LlmProviderAdapter providerAdapter,
            TrajectoryStore trajectoryStore,
            ObjectMapper objectMapper
    ) {
        this.providerAdapter = providerAdapter;
        this.trajectoryStore = trajectoryStore;
        this.objectMapper = objectMapper;
    }

    public LlmStreamResult executeAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String model,
            LlmParams params,
            List<LlmMessage> messages,
            List<Tool> allowedTools,
            AgentEventSink sink
    ) throws Exception {
        try {
            LlmStreamResult result = providerAdapter.streamChat(
                    new LlmChatRequest(
                            runId,
                            attemptId,
                            model,
                            params == null ? null : params.temperature(),
                            params == null ? null : params.maxTokens(),
                            messages,
                            allowedTools.stream().map(Tool::schema).toList()
                    ),
                    delta -> sink.onTextDelta(new TextDeltaEvent(runId, attemptId, delta))
            );
            writeSucceededAttempt(runId, turnNo, attemptId, model, result);
            return result;
        } catch (Exception e) {
            writeFailedAttempt(runId, turnNo, attemptId, model, e);
            throw e;
        }
    }

    private void writeSucceededAttempt(
            String runId,
            int turnNo,
            String attemptId,
            String model,
            LlmStreamResult result
    ) {
        LlmUsage usage = result.usage();
        trajectoryStore.writeLlmAttempt(
                attemptId,
                runId,
                turnNo,
                "deepseek",
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

    private void writeFailedAttempt(String runId, int turnNo, String attemptId, String model, Exception e) {
        trajectoryStore.writeLlmAttempt(
                attemptId,
                runId,
                turnNo,
                "deepseek",
                model,
                "FAILED",
                FinishReason.ERROR,
                null,
                null,
                null,
                errorJson("provider_failed", e.getMessage()),
                null
        );
    }

    private String errorJson(String type, String message) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(Map.of(
                    "type", type,
                    "message", message == null ? "" : message
            )));
        } catch (Exception e) {
            return "{\"type\":\"" + type + "\"}";
        }
    }
}
