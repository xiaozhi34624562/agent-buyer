package com.ai.agent.application;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.ContinueRunRequest;
import com.ai.agent.web.dto.LlmParams;
import com.ai.agent.web.dto.UserMessage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class AgentRequestPolicy {
    private final AgentProperties properties;

    public AgentRequestPolicy(AgentProperties properties) {
        this.properties = properties;
    }

    public void validateCreateRun(AgentRunRequest request) {
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new InvalidAgentRequestException("messages are required");
        }
        validateMessages(request.messages());
        validateLlmParams(request.llmParams());
    }

    public void validateContinueRun(ContinueRunRequest request) {
        if (request == null || request.message() == null) {
            throw new InvalidAgentRequestException("message is required");
        }
        validateMessages(List.of(request.message()));
    }

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

    public static final class InvalidAgentRequestException extends RuntimeException {
        public InvalidAgentRequestException(String message) {
            super(message);
        }
    }
}
