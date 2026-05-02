package com.ai.agent.llm.provider;

/**
 * LLM流式输出监听器接口。
 * 用于接收LLM生成过程中的文本增量输出。
 */
public interface LlmStreamListener {
    /**
     * 接收文本增量输出。
     *
     * @param delta 文本增量内容
     */
    void onTextDelta(String delta);
}
