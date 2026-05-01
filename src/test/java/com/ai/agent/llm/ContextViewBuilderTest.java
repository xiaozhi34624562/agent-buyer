package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.trajectory.TrajectoryReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextViewBuilderTest {
    @Test
    void buildsProviderViewFromRawTrajectoryWithoutMutatingRawMessages() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.user("u1", "cancel order"));
        reader.messages.add(LlmMessage.assistant("a1", null, List.of(new ToolCallMessage("call_1", "query_order", "{}"))));
        reader.messages.add(LlmMessage.tool("t1", "call_1", "{\"ok\":true}"));
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                noOpSpiller(),
                noOpMicroCompactor(),
                noOpSummaryCompactor()
        );

        ProviderContextView view = builder.build("run-1");

        assertThat(view.messages()).hasSize(3);
        assertThat(view.messages()).isNotSameAs(reader.messages);
        assertThat(view.messages()).containsExactlyElementsOf(reader.messages);
        assertThat(reader.loadCount).isEqualTo(1);
    }

    @Test
    void rejectsInvalidRawTrajectoryBeforeProviderRequest() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.tool("t1", "missing", "{}"));
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                noOpSpiller(),
                noOpMicroCompactor(),
                noOpSummaryCompactor()
        );

        assertThatThrownBy(() -> builder.build("run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan tool result");
    }

    @Test
    void spillsLargeToolResultAfterRawValidationWithoutMutatingRawTrajectory() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.assistant("a1", null, List.of(new ToolCallMessage("call_1", "query_order", "{}"))));
        LlmMessage rawTool = LlmMessage.tool("t1", "call_1", tokenText(18));
        reader.messages.add(rawTool);
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(12);
        properties.getContext().setLargeResultHeadTokens(3);
        properties.getContext().setLargeResultTailTokens(2);
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                new LargeResultSpiller(properties, new TokenEstimator()),
                noOpMicroCompactor(),
                noOpSummaryCompactor()
        );

        ProviderContextView view = builder.build("run-1");

        new TranscriptPairValidator().validate(view.messages());
        assertThat(view.messages().get(1).content())
                .contains("<resultPath>trajectory://runs/run-1/tool-results/call_1/full</resultPath>");
        assertThat(rawTool.content()).isEqualTo(tokenText(18));
        assertThat(reader.messages.get(1).content()).isEqualTo(tokenText(18));
    }

    @Test
    void microCompactsAfterSpillingWithoutMutatingRawTrajectory() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.assistant("a-old", null, List.of(new ToolCallMessage("call_old", "query_order", "{}"))));
        LlmMessage rawOldTool = LlmMessage.tool("t-old", "call_old", tokenText(18));
        reader.messages.add(rawOldTool);
        reader.messages.add(LlmMessage.user("u1", tokenText(18)));
        reader.messages.add(LlmMessage.user("u2", tokenText(18)));
        reader.messages.add(LlmMessage.user("u3", tokenText(18)));
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(12);
        properties.getContext().setLargeResultHeadTokens(3);
        properties.getContext().setLargeResultTailTokens(2);
        properties.getContext().setMicroCompactThresholdTokens(12);
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                new LargeResultSpiller(properties, new TokenEstimator()),
                new MicroCompactor(properties, new TokenEstimator()),
                noOpSummaryCompactor()
        );

        ProviderContextView view = builder.build("run-1");

        new TranscriptPairValidator().validate(view.messages());
        assertThat(view.messages().get(1).content()).isEqualTo(MicroCompactor.OLD_TOOL_RESULT_PLACEHOLDER);
        assertThat(rawOldTool.content()).isEqualTo(tokenText(18));
        assertThat(reader.messages.get(1).content()).isEqualTo(tokenText(18));
    }

    @Test
    void summaryCompactsAfterMicroCompactionWithoutMutatingRawTrajectory() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.system("s1", "system prompt"));
        reader.messages.add(LlmMessage.user("u1", "first"));
        reader.messages.add(LlmMessage.assistant("a1", "second", List.of()));
        reader.messages.add(LlmMessage.user("u2", "third"));
        LlmMessage rawOldMessage = LlmMessage.user("u3", tokenText(18));
        reader.messages.add(rawOldMessage);
        reader.messages.add(LlmMessage.user("u4", "recent one"));
        reader.messages.add(LlmMessage.assistant("a2", "recent two", List.of()));
        reader.messages.add(LlmMessage.user("u5", "recent three"));
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(Integer.MAX_VALUE);
        properties.getContext().setMicroCompactThresholdTokens(12);
        properties.getContext().setSummaryCompactThresholdTokens(12);
        properties.getContext().setRecentMessageBudgetTokens(3);
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                new LargeResultSpiller(properties, new TokenEstimator()),
                new MicroCompactor(properties, new TokenEstimator()),
                new SummaryCompactor(
                        properties,
                        new TokenEstimator(),
                        new DeterministicSummaryGenerator(new ObjectMapper()),
                        new ObjectMapper()
                )
        );

        ProviderContextView view = builder.build("run-1");

        new TranscriptPairValidator().validate(view.messages());
        assertThat(view.messages()).extracting(LlmMessage::messageId)
                .containsExactly("s1", "u1", "a1", "u2", "summary-u3", "u4", "a2", "u5");
        assertThat(view.messages().get(4).extras()).containsEntry("compactSummary", true);
        assertThat(view.messages().get(4).extras()).containsEntry("compactedMessageIds", List.of("u3"));
        assertThat(rawOldMessage.content()).isEqualTo(tokenText(18));
        assertThat(reader.messages.get(4).content()).isEqualTo(tokenText(18));
    }

    private static LargeResultSpiller noOpSpiller() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setLargeResultThresholdTokens(Integer.MAX_VALUE);
        return new LargeResultSpiller(properties, new TokenEstimator());
    }

    private static MicroCompactor noOpMicroCompactor() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setMicroCompactThresholdTokens(Integer.MAX_VALUE);
        return new MicroCompactor(properties, new TokenEstimator());
    }

    private static SummaryCompactor noOpSummaryCompactor() {
        AgentProperties properties = new AgentProperties();
        properties.getContext().setSummaryCompactThresholdTokens(Integer.MAX_VALUE);
        return new SummaryCompactor(
                properties,
                new TokenEstimator(),
                new DeterministicSummaryGenerator(new ObjectMapper()),
                new ObjectMapper()
        );
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

    private static final class FakeTrajectoryReader implements TrajectoryReader {
        private final List<LlmMessage> messages = new ArrayList<>();
        private int loadCount;

        @Override
        public List<LlmMessage> loadMessages(String runId) {
            loadCount++;
            return messages;
        }

        @Override
        public List<ToolCall> findToolCallsByRun(String runId) {
            return List.of();
        }
    }
}
