package com.ai.agent.llm.provider;

import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmStreamResult;

/**
 * LLM提供者适配器接口。
 * 定义与大语言模型提供者交互的标准接口，支持流式对话调用。
 */
public interface LlmProviderAdapter {
    /**
     * 获取提供者名称。
     *
     * @return 提供者标识名称
     */
    String providerName();

    /**
     * 获取默认模型名称。
     *
     * @return 默认模型标识
     */
    String defaultModel();

    /**
     * 执行流式对话调用。
     *
     * @param request  对话请求
     * @param listener 流式输出监听器
     * @return 对话结果
     */
    LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener);
}
