package com.ai.agent.llm;

import com.ai.agent.tool.ToolCall;
import com.ai.agent.trajectory.TrajectoryReader;
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
        ContextViewBuilder builder = new ContextViewBuilder(reader, new TranscriptPairValidator());

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
        ContextViewBuilder builder = new ContextViewBuilder(reader, new TranscriptPairValidator());

        assertThatThrownBy(() -> builder.build("run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orphan tool result");
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
