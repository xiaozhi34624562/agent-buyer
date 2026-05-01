package com.ai.agent.llm;

public interface LlmProviderAdapter {
    LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener);
}
