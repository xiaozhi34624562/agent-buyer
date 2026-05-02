package com.ai.agent.llm.provider;

/**
 * LLM调用观察者接口。
 * 用于在LLM调用前执行观察逻辑，如预算检查、调用计数等。
 */
@FunctionalInterface
public interface LlmCallObserver {
    /** 空操作观察者，不执行任何逻辑 */
    LlmCallObserver NOOP = () -> {
    };

    /**
     * 在提供者调用前执行观察逻辑。
     */
    void beforeProviderCall();
}
