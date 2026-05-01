package com.ai.agent.llm;

import com.ai.agent.tool.ToolSchema;

import java.util.List;

public record LlmChatRequest(
        String runId,
        String attemptId,
        String model,
        Double temperature,
        Integer maxTokens,
        List<LlmMessage> messages,
        List<ToolSchema> tools,
        LlmCallObserver callObserver
) {
    public LlmChatRequest(
            String runId,
            String attemptId,
            String model,
            Double temperature,
            Integer maxTokens,
            List<LlmMessage> messages,
            List<ToolSchema> tools
    ) {
        this(runId, attemptId, model, temperature, maxTokens, messages, tools, LlmCallObserver.NOOP);
    }

    public LlmChatRequest {
        callObserver = callObserver == null ? LlmCallObserver.NOOP : callObserver;
    }

    public void beforeProviderCall() {
        callObserver.beforeProviderCall();
    }
}
