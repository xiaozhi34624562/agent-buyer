package com.ai.agent.llm;

@FunctionalInterface
public interface LlmCallObserver {
    LlmCallObserver NOOP = () -> {
    };

    void beforeProviderCall();
}
