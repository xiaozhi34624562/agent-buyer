package com.ai.agent.llm.model;

import com.ai.agent.llm.provider.LlmCallObserver;
import com.ai.agent.tool.core.ToolSchema;
import java.util.List;

/**
 * LLM聊天请求。
 * <p>
 * 封装发送给LLM提供商的聊天请求参数，包括模型配置、消息列表、工具定义和调用观察者。
 * </p>
 */
public record LlmChatRequest(
        String runId,
        String attemptId,
        String model,
        Double temperature,
        Integer maxTokens,
        List<LlmMessage> messages,
        List<ToolSchema> tools,
        LlmCallObserver callObserver
) {
    /**
     * 简化构造函数，使用默认的调用观察者。
     *
     * @param runId       运行ID
     * @param attemptId   尝试ID
     * @param model       模型名称
     * @param temperature 温度参数
     * @param maxTokens   最大token数
     * @param messages    消息列表
     * @param tools       工具schema列表
     */
    public LlmChatRequest(
            String runId,
            String attemptId,
            String model,
            Double temperature,
            Integer maxTokens,
            List<LlmMessage> messages,
            List<ToolSchema> tools
    ) {
        this(runId, attemptId, model, temperature, maxTokens, messages, tools, LlmCallObserver.NOOP);
    }

    /**
     * 紧凑构造函数，确保调用观察者非空。
     */
    public LlmChatRequest {
        callObserver = callObserver == null ? LlmCallObserver.NOOP : callObserver;
    }

    /**
     * 在调用提供商之前触发观察者的回调。
     */
    public void beforeProviderCall() {
        callObserver.beforeProviderCall();
    }
}
