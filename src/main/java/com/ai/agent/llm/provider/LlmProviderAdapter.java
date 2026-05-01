package com.ai.agent.llm.provider;

import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmStreamResult;

public interface LlmProviderAdapter {
    String providerName();

    String defaultModel();

    LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener);
}
