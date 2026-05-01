package com.ai.agent.llm;

public interface LlmProviderAdapter {
    String providerName();

    LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener);
}
