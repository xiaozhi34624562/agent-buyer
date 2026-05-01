package com.ai.agent;

import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.llm.TranscriptPairValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptPairValidatorTest {
    private final TranscriptPairValidator validator = new TranscriptPairValidator();

    @Test
    void acceptsAssistantToolCallFollowedByMatchingToolResult() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("u1", "cancel order"),
                LlmMessage.assistant("a1", null, List.of(new ToolCallMessage("call_1", "query_order", "{}"))),
                LlmMessage.tool("t1", "call_1", "{}")
        );

        assertThatCode(() -> validator.validate(messages)).doesNotThrowAnyException();
    }

    @Test
    void rejectsOrphanToolResult() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("u1", "cancel order"),
                LlmMessage.tool("t1", "missing", "{}")
        );

        assertThatThrownBy(() -> validator.validate(messages))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan tool result");
    }

    @Test
    void rejectsAssistantToolCallWithoutResultBeforeNextProviderRequest() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("u1", "cancel order"),
                LlmMessage.assistant("a1", null, List.of(new ToolCallMessage("call_1", "query_order", "{}")))
        );

        assertThatThrownBy(() -> validator.validate(messages))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing tool result");
    }
}
