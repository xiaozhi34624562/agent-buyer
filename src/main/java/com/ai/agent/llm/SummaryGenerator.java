package com.ai.agent.llm;

import java.util.List;

public interface SummaryGenerator {
    default String generate(String runId, List<LlmMessage> messagesToCompact) {
        return generate(new SummaryGenerationContext(runId, 0, LlmCallObserver.NOOP), messagesToCompact);
    }

    String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact);
}
