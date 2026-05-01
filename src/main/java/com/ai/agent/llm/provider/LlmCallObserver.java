package com.ai.agent.llm.provider;

@FunctionalInterface
public interface LlmCallObserver {
    LlmCallObserver NOOP = () -> {
    };

    void beforeProviderCall();
}
