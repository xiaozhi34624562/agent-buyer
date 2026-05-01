package com.ai.agent.llm.provider;

public interface LlmStreamListener {
    void onTextDelta(String delta);
}
