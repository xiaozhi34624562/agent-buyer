package com.ai.agent.llm;

import java.util.List;

public interface SummaryGenerator {
    String generate(String runId, List<LlmMessage> messagesToCompact);
}
