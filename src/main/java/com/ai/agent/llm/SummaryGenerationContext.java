package com.ai.agent.llm;

import com.ai.agent.trajectory.RunContext;

public record SummaryGenerationContext(
        String runId,
        int turnNo,
        RunContext runContext,
        LlmCallObserver callObserver
) {
    public SummaryGenerationContext(String runId, int turnNo, LlmCallObserver callObserver) {
        this(runId, turnNo, null, callObserver);
    }

    public SummaryGenerationContext {
        callObserver = callObserver == null ? LlmCallObserver.NOOP : callObserver;
    }
}
