package com.ai.agent.llm;

public record SummaryGenerationContext(
        String runId,
        int turnNo,
        LlmCallObserver callObserver
) {
    public SummaryGenerationContext {
        callObserver = callObserver == null ? LlmCallObserver.NOOP : callObserver;
    }
}
