package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRequestPolicyTest {
    @Test
    void defaultPolicyAcceptsExistingMinimalRequestShape() {
        AgentRequestPolicy policy = new AgentRequestPolicy(new AgentProperties());

        assertThatCode(() -> policy.validateCreateRun(
                new AgentRunRequest(List.of(new UserMessage("user", "hello")), null, null)
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsTooManyMessagesWithStableException() {
        AgentProperties properties = new AgentProperties();
        properties.getRequestPolicy().setMaxMessages(1);
        AgentRequestPolicy policy = new AgentRequestPolicy(properties);

        assertThatThrownBy(() -> policy.validateCreateRun(new AgentRunRequest(
                List.of(new UserMessage("user", "one"), new UserMessage("user", "two")),
                null,
                null
        )))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("message count exceeds limit");
    }

    @Test
    void rejectsSingleMessageContentExceedingLimit() {
        AgentProperties properties = new AgentProperties();
        properties.getRequestPolicy().setMaxContentChars(3);
        AgentRequestPolicy policy = new AgentRequestPolicy(properties);

        assertThatThrownBy(() -> policy.validateCreateRun(new AgentRunRequest(
                List.of(new UserMessage("user", "four")),
                null,
                null
        )))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("message content exceeds limit");
    }

    @Test
    void rejectsTotalContentExceedingLimit() {
        AgentProperties properties = new AgentProperties();
        properties.getRequestPolicy().setMaxTotalContentChars(6);
        AgentRequestPolicy policy = new AgentRequestPolicy(properties);

        assertThatThrownBy(() -> policy.validateCreateRun(new AgentRunRequest(
                List.of(new UserMessage("user", "hello"), new UserMessage("user", "hi")),
                null,
                null
        )))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("total message content exceeds limit");
    }

    @Test
    void rejectsLlmParamsOutsideConfiguredBounds() {
        AgentProperties properties = new AgentProperties();
        properties.getRequestPolicy().setAllowedModels(List.of("deepseek-reasoner"));
        AgentRequestPolicy policy = new AgentRequestPolicy(properties);

        assertThatThrownBy(() -> policy.validateCreateRun(requestWith(new LlmParams("other-model", 0.7, 1024, 3))))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("model is not allowed");

        assertThatThrownBy(() -> policy.validateCreateRun(requestWith(new LlmParams("deepseek-reasoner", -0.1, 1024, 3))))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("temperature is out of range");

        assertThatThrownBy(() -> policy.validateCreateRun(requestWith(new LlmParams("deepseek-reasoner", 0.7, 0, 3))))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("maxTokens is out of range");

        assertThatThrownBy(() -> policy.validateCreateRun(requestWith(new LlmParams("deepseek-reasoner", 0.7, 1024, 0))))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("maxTurns is out of range");
    }

    @Test
    void acceptsLlmParamsWithinConfiguredBounds() {
        AgentProperties properties = new AgentProperties();
        properties.getRequestPolicy().setAllowedModels(List.of("deepseek-reasoner"));
        AgentRequestPolicy policy = new AgentRequestPolicy(properties);

        assertThatCode(() -> policy.validateCreateRun(
                requestWith(new LlmParams("deepseek-reasoner", 1.0, 2048, 5))
        )).doesNotThrowAnyException();
    }

    @Test
    void defaultPolicyDoesNotExposeQwenModelBeforeProviderRoutingExists() {
        AgentRequestPolicy policy = new AgentRequestPolicy(new AgentProperties());

        assertThatThrownBy(() -> policy.validateCreateRun(requestWith(new LlmParams("qwen-plus", 0.7, 1024, 3))))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("model is not allowed");
    }

    @Test
    void rejectsContinuationMessageExceedingContentLimit() {
        AgentProperties properties = new AgentProperties();
        properties.getRequestPolicy().setMaxContentChars(3);
        AgentRequestPolicy policy = new AgentRequestPolicy(properties);

        assertThatThrownBy(() -> policy.validateContinueRun(new ContinueRunRequest(new UserMessage("user", "four"))))
                .isInstanceOf(AgentRequestPolicy.InvalidAgentRequestException.class)
                .hasMessage("message content exceeds limit");
    }

    private static AgentRunRequest requestWith(LlmParams params) {
        return new AgentRunRequest(List.of(new UserMessage("user", "hello")), null, params);
    }
}
