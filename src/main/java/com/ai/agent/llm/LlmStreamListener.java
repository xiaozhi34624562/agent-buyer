package com.ai.agent.llm;

public interface LlmStreamListener {
    void onTextDelta(String delta);
}
