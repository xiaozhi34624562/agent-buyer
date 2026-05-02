package com.ai.agent.application;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.ContinueRunRequest;
import com.ai.agent.web.dto.LlmParams;
import com.ai.agent.web.dto.UserMessage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Agent请求策略校验器
 * <p>
 * 负责对Agent运行请求进行参数校验，包括消息内容、LLM参数等，
 * 确保请求符合配置的策略约束，防止无效或超限请求进入系统。
 * </p>
 */
@Component
public final class AgentRequestPolicy {
    private final AgentProperties properties;

    public AgentRequestPolicy(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验创建运行请求
     *
     * @param request Agent运行请求，包含消息列表和LLM参数
     * @throws InvalidAgentRequestException 当请求不满足策略约束时抛出
     */
    public void validateCreateRun(AgentRunRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new InvalidAgentRequestException("messages are required");
        }
        validateMessages(request.messages());
        validateLlmParams(request.llmParams());
    }

    /**
     * 校验继续运行请求
     *
     * @param request 继续运行请求，包含用户消息
     * @throws InvalidAgentRequestException 当请求不满足策略约束时抛出
     */
    public void validateContinueRun(ContinueRunRequest request) {
        if (request == null || request.message() == null) {
            throw new InvalidAgentRequestException("message is required");
        }
        validateMessages(List.of(request.message()));
    }

    /**
     * 校验消息列表
     * <p>
     * 检查消息数量、单条消息内容长度、总内容长度是否在允许范围内。
     * </p>
     *
     * @param messages 用户消息列表
     * @throws InvalidAgentRequestException 当消息不满足约束时抛出
     */
    private void validateMessages(List<UserMessage> messages) {
        AgentProperties.RequestPolicy policy = properties.getRequestPolicy();
        if (messages.size() > policy.getMaxMessages()) {
            throw new InvalidAgentRequestException("message count exceeds limit");
        }
        int totalContentChars = 0;
        for (UserMessage message : messages) {
            String content = message == null ? null : message.content();
            if (content == null || content.isBlank()) {
                throw new InvalidAgentRequestException("message content is required");
            }
            if (content.length() > policy.getMaxContentChars()) {
                throw new InvalidAgentRequestException("message content exceeds limit");
            }
            totalContentChars += content.length();
            if (totalContentChars > policy.getMaxTotalContentChars()) {
                throw new InvalidAgentRequestException("total message content exceeds limit");
            }
        }
    }

    /**
     * 校验LLM参数
     * <p>
     * 检查模型是否在允许列表中，temperature、maxTokens、maxTurns是否在有效范围内。
     * </p>
     *
     * @param params LLM参数对象
     * @throws InvalidAgentRequestException 当参数不满足约束时抛出
     */
    private void validateLlmParams(LlmParams params) {
        if (params == null) {
            return;
        }
        AgentProperties.RequestPolicy policy = properties.getRequestPolicy();
        if (params.model() != null && !params.model().isBlank() && !policy.getAllowedModels().contains(params.model())) {
            throw new InvalidAgentRequestException("model is not allowed");
        }
        if (params.temperature() != null
                && (params.temperature() < policy.getMinTemperature() || params.temperature() > policy.getMaxTemperature())) {
            throw new InvalidAgentRequestException("temperature is out of range");
        }
        if (params.maxTokens() != null
                && (params.maxTokens() < policy.getMinMaxTokens() || params.maxTokens() > policy.getMaxMaxTokens())) {
            throw new InvalidAgentRequestException("maxTokens is out of range");
        }
        if (params.maxTurns() != null
                && (params.maxTurns() < policy.getMinMaxTurns() || params.maxTurns() > policy.getMaxMaxTurns())) {
            throw new InvalidAgentRequestException("maxTurns is out of range");
        }
    }

    /**
     * 无效Agent请求异常
     * <p>
     * 当请求参数不符合策略约束时抛出此异常。
     * </p>
     */
    public static final class InvalidAgentRequestException extends RuntimeException {
        public InvalidAgentRequestException(String message) {
            super(message);
        }
    }
}
