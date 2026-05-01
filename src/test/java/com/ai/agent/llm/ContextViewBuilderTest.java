package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.skill.SkillCommandResolver;
import com.ai.agent.skill.SkillPathResolver;
import com.ai.agent.skill.SkillRegistry;
import com.ai.agent.todo.TodoDraft;
import com.ai.agent.todo.TodoReminderInjector;
import com.ai.agent.todo.TodoStatus;
import com.ai.agent.todo.TodoStep;
import com.ai.agent.todo.TodoStore;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.ContextCompactionDraft;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextViewBuilderTest {
    @TempDir
    Path skillsRoot;

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
        LlmMessage rawOldMessage = LlmMessage.user("u3", tokenText(200));
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
                        (runId, messages) -> """
                                {"summaryText":"short","businessFacts":[],"toolFacts":[],"openQuestions":[],"compactedMessageIds":["u3"]}
                                """.trim(),
                        new ObjectMapper()
                )
        );

        ProviderContextView view = builder.build("run-1");

        new TranscriptPairValidator().validate(view.messages());
        assertThat(view.messages()).extracting(LlmMessage::messageId)
                .containsExactly("s1", "u1", "a1", "u2", "summary-u3", "u4", "a2", "u5");
        assertThat(view.messages().get(4).extras()).containsEntry("compactSummary", true);
        assertThat(view.messages().get(4).extras()).containsEntry("compactedMessageIds", List.of("u3"));
        assertThat(view.compactions()).hasSize(1)
                .first()
                .satisfies(record -> {
                    assertThat(record.strategy()).isEqualTo("SUMMARY_COMPACT");
                    assertThat(record.beforeTokens()).isGreaterThan(record.afterTokens());
                    assertThat(record.compactedMessageIds()).containsExactly("u3");
                });
        assertThat(rawOldMessage.content()).isEqualTo(tokenText(200));
        assertThat(reader.messages.get(4).content()).isEqualTo(tokenText(200));
    }

    @Test
    void recordsLargeAndMicroCompactionsWhenProviderViewChanges() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.assistant("a-old", null, List.of(new ToolCallMessage("call_old", "query_order", "{}"))));
        reader.messages.add(LlmMessage.tool("t-old", "call_old", tokenText(18)));
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

        assertThat(view.compactions()).hasSize(2);
        assertThat(view.compactions().get(0).strategy()).isEqualTo("LARGE_RESULT_SPILL");
        assertThat(view.compactions().get(0).compactedMessageIds()).containsExactly("t-old");
        assertThat(view.compactions().get(1).strategy()).isEqualTo("MICRO_COMPACT");
        assertThat(view.compactions().get(1).compactedMessageIds()).containsExactly("t-old");
    }

    @Test
    void compactsFiftyThousandTokenProviderViewWithoutBreakingToolPairing() throws Exception {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        String rawLargeToolResultContent = exactTokenText(2_100);
        LlmMessage rawLargeToolResult = LlmMessage.tool("t-old", "call_old", rawLargeToolResultContent);
        reader.messages.add(LlmMessage.system("s1", "system prompt"));
        reader.messages.add(LlmMessage.user("u1", "first user message"));
        reader.messages.add(LlmMessage.assistant("a1", "second assistant message", List.of()));
        reader.messages.add(LlmMessage.user("u2", "third user message"));
        reader.messages.add(LlmMessage.user("u-old-business", exactTokenText(55_000)));
        reader.messages.add(LlmMessage.assistant("a-tool", null, List.of(new ToolCallMessage("call_old", "query_order", "{}"))));
        reader.messages.add(rawLargeToolResult);
        reader.messages.add(LlmMessage.user("u-recent-1", exactTokenText(800)));
        reader.messages.add(LlmMessage.assistant("a-recent-2", exactTokenText(800), List.of()));
        reader.messages.add(LlmMessage.user("u-recent-3", exactTokenText(800)));
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setHardTokenCap(30_000);
        properties.getContext().setLargeResultThresholdTokens(2_000);
        properties.getContext().setLargeResultHeadTokens(200);
        properties.getContext().setLargeResultTailTokens(200);
        properties.getContext().setMicroCompactThresholdTokens(50_000);
        properties.getContext().setSummaryCompactThresholdTokens(30_000);
        properties.getContext().setRecentMessageBudgetTokens(2_000);
        ObjectMapper objectMapper = new ObjectMapper();
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                new LargeResultSpiller(properties, new TokenEstimator()),
                new MicroCompactor(properties, new TokenEstimator()),
                new SummaryCompactor(
                        properties,
                        new TokenEstimator(),
                        new DeterministicSummaryGenerator(objectMapper),
                        objectMapper
                )
        );

        ProviderContextView view = builder.build("run-50k");

        new TranscriptPairValidator().validate(view.messages());
        assertThat(view.compactions()).extracting(ContextCompactionDraft::strategy)
                .containsExactly("LARGE_RESULT_SPILL", "MICRO_COMPACT", "SUMMARY_COMPACT");
        assertThat(view.compactions().get(0).compactedMessageIds()).containsExactly("t-old");
        assertThat(view.compactions().get(1).compactedMessageIds()).containsExactly("t-old");
        assertThat(view.compactions().get(2).compactedMessageIds())
                .containsExactly("u-old-business", "a-tool", "t-old");
        LlmMessage summaryMessage = view.messages().stream()
                .filter(message -> Boolean.TRUE.equals(message.extras().get("compactSummary")))
                .findFirst()
                .orElseThrow();
        JsonNode summary = objectMapper.readTree(summaryMessage.content());
        assertThat(summary.get("summaryText").isTextual()).isTrue();
        assertThat(summary.get("businessFacts").isArray()).isTrue();
        assertThat(summary.get("toolFacts").isArray()).isTrue();
        assertThat(summary.get("openQuestions").isArray()).isTrue();
        List<String> compactedIds = new ArrayList<>();
        summary.get("compactedMessageIds").forEach(id -> compactedIds.add(id.asText()));
        assertThat(compactedIds)
                .containsExactly("u-old-business", "a-tool", "t-old");
        assertThat(rawLargeToolResult.content()).isEqualTo(rawLargeToolResultContent);
        assertThat(reader.messages.get(6).content()).isEqualTo(rawLargeToolResultContent);
    }

    @Test
    void injectsSlashSkillAsTransientProviderMessageAndWritesEvent() throws IOException {
        Path skillDir = Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: purchase-guide
                description: 买货指南
                ---

                # Purchase

                full skill content
                """);
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        store.messages.add(LlmMessage.system("s1", "system"));
        store.messages.add(LlmMessage.user("u1", "help me with /purchase-guide"));
        AgentProperties properties = new AgentProperties();
        properties.getSkills().setEnabledSkillNames(List.of("purchase-guide"));
        ObjectMapper objectMapper = new ObjectMapper();
        ContextViewBuilder builder = new ContextViewBuilder(
                store,
                new TranscriptPairValidator(),
                noOpSpiller(),
                noOpMicroCompactor(),
                noOpSummaryCompactor(),
                new SkillCommandResolver(
                        properties,
                        new SkillRegistry(skillsRoot, List.of("purchase-guide")),
                        new SkillPathResolver(skillsRoot),
                        objectMapper
                ),
                store,
                objectMapper
        );

        ProviderContextView view = builder.build("run-1", 2, null, LlmCallObserver.NOOP);

        assertThat(view.messages()).hasSize(3);
        assertThat(view.messages().get(2).messageId()).startsWith("transient-skills-");
        assertThat(view.messages().get(2).content()).contains("<skill name=\"purchase-guide\">", "full skill content");
        assertThat(store.messages).hasSize(2);
        assertThat(store.events).singleElement()
                .satisfies(event -> assertThat(event)
                        .contains("purchase-guide")
                        .contains("\"turnNo\":2"));
    }

    @Test
    void injectsTodoReminderAsTransientProviderMessageWithoutMutatingTrajectory() {
        FakeTrajectoryReader reader = new FakeTrajectoryReader();
        reader.messages.add(LlmMessage.system("s1", "system"));
        reader.messages.add(LlmMessage.user("u1", "继续"));
        FakeTodoStore todoStore = new FakeTodoStore(List.of(
                new TodoStep("step_1", "查询待取消订单", TodoStatus.PENDING, null, null),
                new TodoStep("step_2", "完成取消", TodoStatus.IN_PROGRESS, "等待工具结果", null)
        ));
        ContextViewBuilder builder = new ContextViewBuilder(
                reader,
                new TranscriptPairValidator(),
                noOpSpiller(),
                noOpMicroCompactor(),
                noOpSummaryCompactor(),
                null,
                new TodoReminderInjector(new AgentProperties(), todoStore),
                null,
                new ObjectMapper()
        );

        ProviderContextView view = builder.build("run-1", 3, null, LlmCallObserver.NOOP);

        assertThat(view.messages()).hasSize(3);
        LlmMessage reminder = view.messages().get(2);
        assertThat(reminder.role()).isEqualTo(MessageRole.USER);
        assertThat(reminder.messageId()).isEqualTo("todo-reminder-run-1-3");
        assertThat(reminder.content()).contains("step_1", "查询待取消订单", "IN_PROGRESS");
        assertThat(reminder.extras()).containsEntry("todoReminder", true);
        assertThat(todoStore.reminderRecorded).isTrue();
        assertThat(reader.messages).hasSize(2);
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

    private static String exactTokenText(int tokenCount) {
        StringBuilder builder = new StringBuilder(tokenCount * 5);
        for (int i = 0; i < tokenCount; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append("word");
        }
        return builder.toString();
    }

    private static class FakeTrajectoryReader implements TrajectoryReader {
        protected final List<LlmMessage> messages = new ArrayList<>();
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

    private static final class FakeTrajectoryStore extends FakeTrajectoryReader implements TrajectoryWriter {
        private final List<String> events = new ArrayList<>();

        @Override
        public void createRun(String runId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateRunStatus(String runId, RunStatus status, String error) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int nextTurn(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String appendMessage(String runId, LlmMessage message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeLlmAttempt(
                String attemptId,
                String runId,
                int turnNo,
                String provider,
                String model,
                String status,
                FinishReason finishReason,
                Integer promptTokens,
                Integer completionTokens,
                Integer totalTokens,
                String errorJson,
                String rawDiagnosticJson
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeAgentEvent(String runId, String eventType, String payloadJson) {
            events.add(eventType + ":" + payloadJson);
        }
    }

    private static final class FakeTodoStore implements TodoStore {
        private final List<TodoStep> openSteps;
        private boolean reminderRecorded;

        private FakeTodoStore(List<TodoStep> openSteps) {
            this.openSteps = openSteps;
        }

        @Override
        public List<TodoStep> replacePlan(String runId, List<TodoDraft> items) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TodoStep updateStep(String runId, String stepId, TodoStatus status, String notes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TodoStep> listSteps(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TodoStep> findOpenTodos(String runId) {
            return openSteps;
        }

        @Override
        public void recordReminder(String runId, int turnNo, List<TodoStep> openSteps) {
            reminderRecorded = true;
        }
    }

}
