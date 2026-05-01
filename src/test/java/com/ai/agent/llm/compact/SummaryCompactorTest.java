package com.ai.agent.llm.compact;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.llm.summary.SummaryGenerationContext;
import com.ai.agent.llm.summary.SummaryGenerator;
import com.ai.agent.llm.transcript.TranscriptPairValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryCompactorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitsAssistantSummaryWithSchemaAndMetadataWhenThresholdIsReached() throws Exception {
        RecordingSummaryGenerator generator = new RecordingSummaryGenerator();
        SummaryCompactor compactor = compactor(generator, 20, 3);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system prompt"),
                LlmMessage.user("u1", "first non system"),
                LlmMessage.assistant("a1", "second non system", List.of()),
                LlmMessage.user("u2", "third non system"),
                LlmMessage.user("u3", tokenText(12)),
                LlmMessage.assistant("a2", "historical assistant", List.of()),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.assistant("a3", "recent two", List.of()),
                LlmMessage.user("u5", "recent three")
        );

        List<LlmMessage> compacted = compactor.compact("run-1", messages);

        assertThat(compacted).extracting(LlmMessage::messageId)
                .containsExactly("s1", "u1", "a1", "u2", "summary-u3", "u4", "a3", "u5");
        LlmMessage summary = compacted.get(4);
        assertThat(summary.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(summary.toolCalls()).isEmpty();
        assertThat(summary.extras()).containsEntry("compactSummary", true);
        assertThat(summary.extras()).containsEntry("compactedMessageIds", List.of("u3", "a2"));

        Map<String, Object> json = objectMapper.readValue(summary.content(), new TypeReference<>() {});
        assertThat(json).containsKeys(
                "summaryText",
                "businessFacts",
                "toolFacts",
                "openQuestions",
                "compactedMessageIds"
        );
        assertThat(json.get("compactedMessageIds")).isEqualTo(List.of("u3", "a2"));
        assertThat(generator.seenMessageIds()).containsExactly("u3", "a2");
        new TranscriptPairValidator().validate(compacted);
    }

    @Test
    void keepsAdditionalRecentMessagesWhileTheyFitBudget() {
        SummaryCompactor compactor = compactor(new RecordingSummaryGenerator(), 14, 9);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system"),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of()),
                LlmMessage.user("u2", "third"),
                LlmMessage.user("u3", tokenText(8)),
                LlmMessage.assistant("a2", "one two three", List.of()),
                LlmMessage.user("u4", "four five"),
                LlmMessage.assistant("a3", "six", List.of()),
                LlmMessage.user("u5", "seven")
        );

        List<LlmMessage> compacted = compactor.compact("run-1", messages);

        assertThat(compacted).extracting(LlmMessage::messageId)
                .containsExactly("s1", "u1", "a1", "u2", "summary-u3", "a2", "u4", "a3", "u5");
        assertThat(compacted.get(4).extras()).containsEntry("compactedMessageIds", List.of("u3"));
    }

    @Test
    void keepsAssistantToolCallAndMatchingToolResultTogether() {
        SummaryCompactor compactor = compactor(new RecordingSummaryGenerator(), 18, 2);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system"),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of()),
                LlmMessage.user("u2", "third"),
                LlmMessage.assistant("a2", "query", List.of(new ToolCallMessage("call-1", "query_order", "{}"))),
                LlmMessage.tool("t1", "call-1", tokenText(8)),
                LlmMessage.user("u3", "recent one"),
                LlmMessage.assistant("a3", "recent two", List.of()),
                LlmMessage.user("u4", "recent three")
        );

        List<LlmMessage> compacted = compactor.compact("run-1", messages);

        assertThat(compacted).extracting(LlmMessage::messageId)
                .containsExactly("s1", "u1", "a1", "u2", "summary-a2", "u3", "a3", "u4");
        assertThat(compacted.get(4).extras())
                .containsEntry("compactedMessageIds", List.of("a2", "t1"));
        new TranscriptPairValidator().validate(compacted);
    }

    @Test
    void preservedToolCallBlockKeepsRawMessageOrder() {
        SummaryCompactor compactor = compactor(new RecordingSummaryGenerator(), 8, 1);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system"),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of()),
                LlmMessage.user("u2", "third"),
                LlmMessage.user("u3", tokenText(12)),
                LlmMessage.assistant("a2", "query", List.of(
                        new ToolCallMessage("call-1", "query_order", "{}"),
                        new ToolCallMessage("call-2", "query_order", "{}")
                )),
                LlmMessage.tool("t2", "call-2", "result two"),
                LlmMessage.tool("t1", "call-1", "result one"),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.user("u5", "recent two")
        );

        List<LlmMessage> compacted = compactor.compact("run-1", messages);

        assertThat(compacted).extracting(LlmMessage::messageId)
                .containsExactly("s1", "u1", "a1", "u2", "summary-u3", "a2", "t2", "t1", "u4", "u5");
        new TranscriptPairValidator().validate(compacted);
    }

    @Test
    void rejectsSummaryJsonWithMissingSchemaFields() {
        SummaryCompactor compactor = compactor((runId, messages) -> "{\"summaryText\":\"missing fields\"}", 20, 3);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system prompt"),
                LlmMessage.user("u1", "first non system"),
                LlmMessage.assistant("a1", "second non system", List.of()),
                LlmMessage.user("u2", "third non system"),
                LlmMessage.user("u3", tokenText(12)),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.assistant("a3", "recent two", List.of()),
                LlmMessage.user("u5", "recent three")
        );

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> compactor.compact("run-1", messages));
    }

    @Test
    void rejectsSummaryJsonWithMismatchedCompactedMessageIds() {
        SummaryCompactor compactor = compactor((runId, messages) -> """
                {"summaryText":"summary","businessFacts":[],"toolFacts":[],"openQuestions":[],"compactedMessageIds":["wrong"]}
                """.trim(), 20, 3);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system prompt"),
                LlmMessage.user("u1", "first non system"),
                LlmMessage.assistant("a1", "second non system", List.of()),
                LlmMessage.user("u2", "third non system"),
                LlmMessage.user("u3", tokenText(12)),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.assistant("a3", "recent two", List.of()),
                LlmMessage.user("u5", "recent three")
        );

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> compactor.compact("run-1", messages));

    }

    @Test
    void failsClosedWhenCompactedViewStillExceedsHardTokenCap() {
        RecordingSummaryGenerator generator = new RecordingSummaryGenerator();
        AgentProperties properties = new AgentProperties();
        properties.getContext().setSummaryCompactThresholdTokens(1);
        properties.getContext().setRecentMessageBudgetTokens(1);
        properties.getAgentLoop().setHardTokenCap(6);
        SummaryCompactor compactor = new SummaryCompactor(
                properties,
                new TokenEstimator(),
                generator,
                new ObjectMapper()
        );
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", tokenText(20)),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of()),
                LlmMessage.user("u2", "third"),
                LlmMessage.user("u3", tokenText(20)),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.assistant("a2", "recent two", List.of()),
                LlmMessage.user("u5", "recent three")
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> compactor.compact("run-1", messages)
        );
    }

    @Test
    void failsClosedWhenThresholdReachedButAllMessagesArePreserved() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setSummaryCompactThresholdTokens(1);
        properties.getContext().setRecentMessageBudgetTokens(100);
        properties.getAgentLoop().setHardTokenCap(6);
        SummaryCompactor compactor = new SummaryCompactor(
                properties,
                new TokenEstimator(),
                new RecordingSummaryGenerator(),
                new ObjectMapper()
        );
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", tokenText(16)),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of())
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> compactor.compact("run-1", messages)
        );
    }

    @Test
    void leavesMessagesUnchangedBelowThreshold() {
        SummaryCompactor compactor = compactor(new RecordingSummaryGenerator(), 100, 2);
        List<LlmMessage> messages = List.of(
                LlmMessage.system("s1", "system"),
                LlmMessage.user("u1", "hello")
        );

        List<LlmMessage> compacted = compactor.compact("run-1", messages);

        assertThat(compacted).containsExactlyElementsOf(messages);
    }

    private static SummaryCompactor compactor(SummaryGenerator generator, int thresholdTokens, int recentBudgetTokens) {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setSummaryCompactThresholdTokens(thresholdTokens);
        properties.getContext().setRecentMessageBudgetTokens(recentBudgetTokens);
        return new SummaryCompactor(properties, new TokenEstimator(), generator, new ObjectMapper());
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

    private static final class RecordingSummaryGenerator implements SummaryGenerator {
        private List<String> seenMessageIds = List.of();

        @Override
        public String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact) {
            seenMessageIds = messagesToCompact.stream()
                    .map(LlmMessage::messageId)
                    .toList();
            return """
                    {"summaryText":"summary","businessFacts":[],"toolFacts":[],"openQuestions":[],"compactedMessageIds":%s}
                    """.formatted(toJsonArray(seenMessageIds)).trim();
        }

        private List<String> seenMessageIds() {
            return seenMessageIds;
        }

        private static String toJsonArray(List<String> ids) {
            List<String> escaped = new ArrayList<>();
            for (String id : ids) {
                escaped.add("\"" + id + "\"");
            }
            return "[" + String.join(",", escaped) + "]";
        }
    }
}
