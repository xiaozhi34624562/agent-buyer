package com.ai.agent.llm;

import java.util.List;

public record ProviderContextView(List<LlmMessage> messages) {
    public ProviderContextView {
        messages = List.copyOf(messages);
    }
}
