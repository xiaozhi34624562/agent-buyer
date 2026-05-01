package com.ai.agent.llm.summary;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.provider.LlmCallObserver;
import java.util.List;

public interface SummaryGenerator {
    default String generate(String runId, List<LlmMessage> messagesToCompact) {
        return generate(new SummaryGenerationContext(runId, 0, LlmCallObserver.NOOP), messagesToCompact);
    }

    String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact);
}
