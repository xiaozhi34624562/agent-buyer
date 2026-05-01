package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MicroCompactorTest {
    @Test
    void replacesOldToolResultContentWhenProviderViewReachesThreshold() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setMicroCompactThresholdTokens(12);
        MicroCompactor compactor = new MicroCompactor(properties, new TokenEstimator());
        LlmMessage oldTool = new LlmMessage(
                "tool-old",
                MessageRole.TOOL,
                tokenText(8),
                List.of(),
                "call-old",
                Map.of("trace", "kept")
        );
        List<LlmMessage> messages = List.of(
                LlmMessage.assistant("assistant-old", null, List.of(new ToolCallMessage("call-old", "query_order", "{}"))),
                oldTool,
                LlmMessage.user("user-1", tokenText(8)),
                LlmMessage.user("user-2", tokenText(8)),
                LlmMessage.user("user-3", tokenText(8))
        );

        List<LlmMessage> compacted = compactor.compact(messages);

        assertThat(compacted).hasSize(messages.size());
        assertThat(compacted.get(1).content()).isEqualTo(MicroCompactor.OLD_TOOL_RESULT_PLACEHOLDER);
        assertThat(compacted.get(1).messageId()).isEqualTo(oldTool.messageId());
        assertThat(compacted.get(1).toolUseId()).isEqualTo(oldTool.toolUseId());
        assertThat(compacted.get(1).toolCalls()).isEqualTo(oldTool.toolCalls());
        assertThat(compacted.get(1).extras()).isEqualTo(oldTool.extras());
        assertThat(oldTool.content()).isEqualTo(tokenText(8));
        new TranscriptPairValidator().validate(compacted);
    }

    @Test
    void preservesToolResultsInsideLastThreeMessages() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setMicroCompactThresholdTokens(12);
        MicroCompactor compactor = new MicroCompactor(properties, new TokenEstimator());
        LlmMessage recentTool = LlmMessage.tool("tool-recent", "call-recent", tokenText(8));
        List<LlmMessage> messages = List.of(
                LlmMessage.user("user-1", tokenText(8)),
                LlmMessage.user("user-2", tokenText(8)),
                LlmMessage.assistant("assistant-recent", null, List.of(new ToolCallMessage("call-recent", "query_order", "{}"))),
                recentTool,
                LlmMessage.user("user-3", tokenText(8))
        );

        List<LlmMessage> compacted = compactor.compact(messages);

        assertThat(compacted).containsExactlyElementsOf(messages);
        assertThat(compacted.get(3).content()).isEqualTo(tokenText(8));
        new TranscriptPairValidator().validate(compacted);
    }

    @Test
    void leavesProviderViewBelowThresholdUnchanged() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setMicroCompactThresholdTokens(100);
        MicroCompactor compactor = new MicroCompactor(properties, new TokenEstimator());
        List<LlmMessage> messages = List.of(
                LlmMessage.assistant("assistant-old", null, List.of(new ToolCallMessage("call-old", "query_order", "{}"))),
                LlmMessage.tool("tool-old", "call-old", tokenText(8))
        );

        List<LlmMessage> compacted = compactor.compact(messages);

        assertThat(compacted).containsExactlyElementsOf(messages);
    }

    private static String tokenText(int tokenCount) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append("tok").append(i);
        }
        return builder.toString();
    }
}
