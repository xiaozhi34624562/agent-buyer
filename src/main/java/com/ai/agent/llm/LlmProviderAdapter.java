package com.ai.agent.llm;

public interface LlmProviderAdapter {
    String providerName();

    String defaultModel();

    LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener);
}
