package com.ai.agent.llm.context;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.trajectory.model.ContextCompactionDraft;
import java.util.List;

public record ProviderContextView(List<LlmMessage> messages, List<ContextCompactionDraft> compactions) {
    public ProviderContextView(List<LlmMessage> messages) {
        this(messages, List.of());
    }

    public ProviderContextView {
        messages = List.copyOf(messages);
        compactions = compactions == null ? List.of() : List.copyOf(compactions);
    }
}
