package com.ai.agent.loop;

import com.ai.agent.application.RunStateMachine;
import com.ai.agent.budget.AgentExecutionBudget;
import com.ai.agent.budget.RunLlmCallBudgetStore;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.compact.LargeResultSpiller;
import com.ai.agent.llm.compact.MicroCompactor;
import com.ai.agent.llm.compact.SummaryCompactor;
import com.ai.agent.llm.context.ContextViewBuilder;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmProviderAdapterRegistry;
import com.ai.agent.llm.provider.LlmStreamListener;
import com.ai.agent.llm.summary.DeterministicSummaryGenerator;
import com.ai.agent.llm.summary.SummaryGenerationContext;
import com.ai.agent.llm.summary.SummaryGenerator;
import com.ai.agent.llm.transcript.TranscriptPairValidator;
import com.ai.agent.skill.command.SkillCommandResolver;
import com.ai.agent.skill.core.SkillRegistry;
import com.ai.agent.skill.path.SkillPathResolver;
import com.ai.agent.support.TestObjectProvider;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.ToolResultCloser;
import com.ai.agent.tool.runtime.ToolResultWaiter;
import com.ai.agent.tool.runtime.ToolRuntime;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.model.ContextCompactionRecord;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.ContextCompactionStore;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.FinalEvent;
import com.ai.agent.web.sse.TextDeltaEvent;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.ai.agent.web.sse.ToolResultEvent;
import com.ai.agent.web.sse.ToolUseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentTurnOrchestratorBudgetTest {
    @TempDir
    Path skillsRoot;

    @Test
    void pausesRunAndWritesEventWhenMainTurnBudgetIsExceededBeforeProviderCall() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(0);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", List.of(LlmMessage.user("msg-1", "hello")));
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(properties, trajectoryStore, provider, new InMemoryRunLlmCallBudgetStore());
        CapturingSink sink = new CapturingSink();

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1"), null, sink);

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.statusByRun.get("run-1")).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.events).extracting(EventRecord::eventType).containsExactly("MAIN_TURN_BUDGET");
        assertThat(trajectoryStore.attempts).isEmpty();
        assertThat(provider.networkCalls).isZero();
        assertThat(sink.finals).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(RunStatus.PAUSED);
            assertThat(event.nextActionRequired()).isEqualTo("user_input");
        });
    }

    @Test
    void pausesRunAndWritesEventWhenRunWideBudgetIsExceededBeforeProviderCall() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(30);
        properties.getAgentLoop().setRunWideLlmCallBudget(1);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", List.of(LlmMessage.user("msg-1", "hello")));
        InMemoryRunLlmCallBudgetStore budgetStore = new InMemoryRunLlmCallBudgetStore();
        budgetStore.countsByRun.put("run-1", 1L);
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(properties, trajectoryStore, provider, budgetStore);

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1"), null, new CapturingSink());

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.events).extracting(EventRecord::eventType).containsExactly("RUN_WIDE_BUDGET");
        assertThat(trajectoryStore.attempts).isEmpty();
        assertThat(provider.networkCalls).isZero();
        assertThat(budgetStore.countsByRun.get("run-1")).isEqualTo(1L);
    }

    @Test
    void pausesSubAgentRunAndWritesSubTurnBudgetEvent() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setSubAgentLlmCallBudgetPerUserTurn(0);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("child-run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("child-run-1", List.of(
                LlmMessage.user("msg-1", "hello"),
                LlmMessage.assistant("msg-2", "已找到一个疑似目标订单 O-1001。", List.of())
        ));
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(properties, trajectoryStore, provider, new InMemoryRunLlmCallBudgetStore());

        AgentRunResult result = orchestrator.runSubAgentUntilStop(
                "child-run-1",
                "user-1",
                runContext("child-run-1"),
                null,
                new CapturingSink()
        );

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(result.finalText()).contains("疑似目标订单 O-1001");
        assertThat(trajectoryStore.events).extracting(EventRecord::eventType).containsExactly("SUB_TURN_BUDGET");
        assertThat(provider.networkCalls).isZero();
    }

    @Test
    void subAgentRunWideBudgetUsesParentRunBudgetKey() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setSubAgentLlmCallBudgetPerUserTurn(30);
        properties.getAgentLoop().setRunWideLlmCallBudget(1);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("child-run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("child-run-1", List.of(LlmMessage.user("msg-1", "hello")));
        InMemoryRunLlmCallBudgetStore budgetStore = new InMemoryRunLlmCallBudgetStore();
        budgetStore.countsByRun.put("parent-run-1", 1L);
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(properties, trajectoryStore, provider, budgetStore);

        AgentRunResult result = orchestrator.runSubAgentUntilStop(
                "child-run-1",
                "parent-run-1",
                "user-1",
                runContext("child-run-1"),
                null,
                new CapturingSink()
        );

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.events).extracting(EventRecord::eventType).containsExactly("RUN_WIDE_BUDGET");
        assertThat(provider.networkCalls).isZero();
        assertThat(budgetStore.countsByRun).containsEntry("parent-run-1", 1L);
        assertThat(budgetStore.countsByRun).doesNotContainKey("child-run-1");
    }


    @Test
    void budgetExceededDoesNotPersistCompactionForUnattemptedProviderCall() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(0);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        properties.getContext().setLargeResultThresholdTokens(4);
        properties.getContext().setLargeResultHeadTokens(1);
        properties.getContext().setLargeResultTailTokens(1);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", List.of(
                LlmMessage.assistant("assistant-1", null, List.of(new com.ai.agent.llm.model.ToolCallMessage("call-1", "query_order", "{}"))),
                LlmMessage.tool("tool-1", "call-1", tokenText(12))
        ));
        RecordingCompactionStore compactionStore = new RecordingCompactionStore();
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(
                properties,
                trajectoryStore,
                provider,
                new InMemoryRunLlmCallBudgetStore(),
                compactionStore
        );

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1"), null, new CapturingSink());

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(provider.networkCalls).isZero();
        assertThat(trajectoryStore.attempts).isEmpty();
        assertThat(compactionStore.records).isEmpty();
    }

    @Test
    void budgetExceededBeforeSummaryCompactionDoesNotCallSummaryProvider() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(0);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        properties.getContext().setLargeResultThresholdTokens(Integer.MAX_VALUE);
        properties.getContext().setMicroCompactThresholdTokens(Integer.MAX_VALUE);
        properties.getContext().setSummaryCompactThresholdTokens(1);
        properties.getContext().setRecentMessageBudgetTokens(1);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", List.of(
                LlmMessage.system("s1", "system"),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of()),
                LlmMessage.user("u2", "third"),
                LlmMessage.user("u3", tokenText(20)),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.assistant("a2", "recent two", List.of()),
                LlmMessage.user("u5", "recent three")
        ));
        BudgetedSummaryGenerator summaryGenerator = new BudgetedSummaryGenerator();
        RecordingCompactionStore compactionStore = new RecordingCompactionStore();
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(
                properties,
                trajectoryStore,
                provider,
                new InMemoryRunLlmCallBudgetStore(),
                compactionStore,
                summaryGenerator
        );

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1"), null, new CapturingSink());

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(summaryGenerator.providerCalls).isZero();
        assertThat(provider.networkCalls).isZero();
        assertThat(trajectoryStore.attempts).isEmpty();
        assertThat(compactionStore.records).isEmpty();
        assertThat(trajectoryStore.events).extracting(EventRecord::eventType).containsExactly("MAIN_TURN_BUDGET");
    }

    @Test
    void contextBuildFailureTransitionsRunToFailedAndEmitsError() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(30);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        properties.getContext().setLargeResultThresholdTokens(Integer.MAX_VALUE);
        properties.getContext().setMicroCompactThresholdTokens(Integer.MAX_VALUE);
        properties.getContext().setSummaryCompactThresholdTokens(1);
        properties.getContext().setRecentMessageBudgetTokens(1);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", List.of(
                LlmMessage.system("s1", "system"),
                LlmMessage.user("u1", "first"),
                LlmMessage.assistant("a1", "second", List.of()),
                LlmMessage.user("u2", "third"),
                LlmMessage.user("u3", tokenText(20)),
                LlmMessage.user("u4", "recent one"),
                LlmMessage.assistant("a2", "recent two", List.of()),
                LlmMessage.user("u5", "recent three")
        ));
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(
                properties,
                trajectoryStore,
                provider,
                new InMemoryRunLlmCallBudgetStore(),
                record -> null,
                new ThrowingSummaryGenerator()
        );
        CapturingSink sink = new CapturingSink();

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1"), null, sink);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(trajectoryStore.statusByRun.get("run-1")).isEqualTo(RunStatus.FAILED);
        assertThat(provider.networkCalls).isZero();
        assertThat(sink.errors).singleElement().satisfies(error ->
                assertThat(error.message()).contains("summary exploded")
        );
    }

    @Test
    void slashSkillBudgetFailureEmitsStableErrorContract() throws IOException {
        writeSkill("purchase-guide", "买货指南", "purchase");
        writeSkill("return-exchange-guide", "退换货指南", "return");
        AgentProperties properties = new AgentProperties();
        properties.getSkills().setEnabledSkillNames(List.of("purchase-guide", "return-exchange-guide"));
        properties.getSkills().setMaxPerMessage(1);
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(30);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", List.of(
                LlmMessage.user("u1", "请使用 /purchase-guide /return-exchange-guide")
        ));
        ObjectMapper objectMapper = new ObjectMapper();
        ContextViewBuilder contextViewBuilder = new ContextViewBuilder(
                trajectoryStore,
                new TranscriptPairValidator(),
                new LargeResultSpiller(properties, new TokenEstimator()),
                new MicroCompactor(properties, new TokenEstimator()),
                new SummaryCompactor(
                        properties,
                        new TokenEstimator(),
                        new DeterministicSummaryGenerator(objectMapper),
                        objectMapper
                ),
                new SkillCommandResolver(
                        properties,
                        new SkillRegistry(skillsRoot, List.of("purchase-guide", "return-exchange-guide")),
                        new SkillPathResolver(skillsRoot),
                        objectMapper
                ),
                null,
                trajectoryStore,
                objectMapper
        );
        CountingProvider provider = new CountingProvider();
        AgentTurnOrchestrator orchestrator = orchestrator(
                properties,
                trajectoryStore,
                provider,
                new InMemoryRunLlmCallBudgetStore(),
                record -> null,
                contextViewBuilder,
                objectMapper
        );
        CapturingSink sink = new CapturingSink();

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1"), null, sink);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(provider.networkCalls).isZero();
        assertThat(sink.errors).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("SKILL_BUDGET_EXCEEDED");
            assertThat(error.details())
                    .containsEntry("budget", 1)
                    .containsEntry("actual", 2)
                    .containsEntry("exceeded", 1);
            assertThat(error.details().get("matchedSkills").toString())
                    .contains("purchase-guide")
                    .contains("return-exchange-guide");
        });
    }

    @Test
    void recoverablePrecheckFailurePausesRunForUserInputInsteadOfFailingTheToolLoop() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(30);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        trajectoryStore.statusByRun.put("run-1", RunStatus.RUNNING);
        trajectoryStore.messagesByRun.put("run-1", new ArrayList<>(List.of(LlmMessage.user("u1", "取消张三的订单"))));
        ToolCallingProvider provider = new ToolCallingProvider(new ToolCallMessage("call-1", "cancel_order", "{}"));
        ObjectMapper objectMapper = new ObjectMapper();
        AgentTurnOrchestrator orchestrator = orchestrator(
                properties,
                trajectoryStore,
                provider,
                new InMemoryRunLlmCallBudgetStore(),
                record -> null,
                contextViewBuilder(properties, trajectoryStore, objectMapper),
                objectMapper,
                new ToolRegistry(List.of(new RecoverableCancelOrderTool()))
        );
        CapturingSink sink = new CapturingSink();

        AgentRunResult result = orchestrator.runUntilStop("run-1", "user-1", runContext("run-1", List.of("cancel_order")), null, sink);

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(result.finalText()).contains("订单号");
        assertThat(trajectoryStore.statusByRun.get("run-1")).isEqualTo(RunStatus.PAUSED);
        assertThat(sink.finals).singleElement().satisfies(event -> {
            assertThat(event.status()).isEqualTo(RunStatus.PAUSED);
            assertThat(event.nextActionRequired()).isEqualTo("user_input");
            assertThat(event.finalText()).contains("订单号");
        });
    }

    private static AgentTurnOrchestrator orchestrator(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            LlmProviderAdapter provider,
            RunLlmCallBudgetStore budgetStore
    ) {
        return orchestrator(properties, trajectoryStore, provider, budgetStore, record -> null);
    }

    private static AgentTurnOrchestrator orchestrator(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            LlmProviderAdapter provider,
            RunLlmCallBudgetStore budgetStore,
            ContextCompactionStore compactionStore
    ) {
        return orchestrator(
                properties,
                trajectoryStore,
                provider,
                budgetStore,
                compactionStore,
                new DeterministicSummaryGenerator(new ObjectMapper())
        );
    }

    private static AgentTurnOrchestrator orchestrator(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            LlmProviderAdapter provider,
            RunLlmCallBudgetStore budgetStore,
            ContextCompactionStore compactionStore,
            SummaryGenerator summaryGenerator
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ContextViewBuilder contextViewBuilder = new ContextViewBuilder(
                trajectoryStore,
                new TranscriptPairValidator(),
                new LargeResultSpiller(properties, new TokenEstimator()),
                new MicroCompactor(properties, new TokenEstimator()),
                new SummaryCompactor(
                        properties,
                        new TokenEstimator(),
                        summaryGenerator,
                        objectMapper
                )
        );
        return orchestrator(
                properties,
                trajectoryStore,
                provider,
                budgetStore,
                compactionStore,
                contextViewBuilder,
                objectMapper,
                new ToolRegistry(List.of())
        );
    }

    private static ContextViewBuilder contextViewBuilder(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            ObjectMapper objectMapper
    ) {
        return new ContextViewBuilder(
                trajectoryStore,
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
    }

    private static AgentTurnOrchestrator orchestrator(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            LlmProviderAdapter provider,
            RunLlmCallBudgetStore budgetStore,
            ContextCompactionStore compactionStore,
            ContextViewBuilder contextViewBuilder,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry
    ) {
        return new AgentTurnOrchestrator(
                properties,
                contextViewBuilder,
                new LlmAttemptService(
                        new LlmProviderAdapterRegistry(List.of(provider)),
                        trajectoryStore,
                        objectMapper,
                        compactionStore
                ),
                new ToolCallCoordinator(
                        properties,
                        toolRegistry,
                        (ToolRuntime) (runId, call) -> {
                        },
                        new FakeRedisToolStore(),
                        new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                        trajectoryStore,
                        trajectoryStore,
                        new ToolResultCloser(trajectoryStore, trajectoryStore, TestObjectProvider.empty()),
                        mock(PendingConfirmToolStore.class),
                        objectMapper
                ),
                trajectoryStore,
                trajectoryStore,
                new RunStateMachine(trajectoryStore),
                new AgentExecutionBudget(properties, budgetStore)
        );
    }

    private static AgentTurnOrchestrator orchestrator(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            LlmProviderAdapter provider,
            RunLlmCallBudgetStore budgetStore,
            ContextCompactionStore compactionStore,
            ContextViewBuilder contextViewBuilder,
            ObjectMapper objectMapper
    ) {
        return orchestrator(
                properties,
                trajectoryStore,
                provider,
                budgetStore,
                compactionStore,
                contextViewBuilder,
                objectMapper,
                new ToolRegistry(List.of())
        );
    }

    private void writeSkill(String name, String description, String body) throws IOException {
        Path skillDir = Files.createDirectories(skillsRoot.resolve(name));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                # %s

                %s
                """.formatted(name, description, name, body));
    }

    private static RunContext runContext(String runId) {
        return runContext(runId, List.of());
    }

    private static RunContext runContext(String runId, List<String> tools) {
        return new RunContext(
                runId,
                tools,
                "deepseek-reasoner",
                "deepseek",
                "qwen",
                "{}",
                10,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static final class ToolCallingProvider implements LlmProviderAdapter {
        private final ToolCallMessage toolCall;

        private ToolCallingProvider(ToolCallMessage toolCall) {
            this.toolCall = toolCall;
        }

        @Override
        public String providerName() {
            return "deepseek";
        }

        @Override
        public String defaultModel() {
            return "deepseek-reasoner";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            return new LlmStreamResult("", List.of(toolCall), FinishReason.TOOL_CALLS, null, null);
        }
    }

    private static final class RecoverableCancelOrderTool implements Tool {
        @Override
        public ToolSchema schema() {
            return new ToolSchema(
                    "cancel_order",
                    "Cancel order",
                    "{}",
                    false,
                    false,
                    java.time.Duration.ofSeconds(1),
                    1024,
                    List.of()
            );
        }

        @Override
        public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
            return ToolValidation.rejected("""
                    {"type":"missing_order_id","recoverable":true,"nextActionRequired":"user_input","question":"请提供要取消的订单号。"}
                    """.trim());
        }
    }

    private static final class CountingProvider implements LlmProviderAdapter {
        private int networkCalls;

        @Override
        public String providerName() {
            return "deepseek";
        }

        @Override
        public String defaultModel() {
            return "deepseek-reasoner";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            networkCalls++;
            return new LlmStreamResult("ok", List.of(), FinishReason.STOP, null, null);
        }
    }

    private static final class InMemoryRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
        private final Map<String, Long> countsByRun = new HashMap<>();

        @Override
        public Reservation reserveRunCall(String runId, int limit) {
            long current = countsByRun.getOrDefault(runId, 0L);
            if (current >= limit) {
                return new Reservation(false, current);
            }
            long next = current + 1L;
            countsByRun.put(runId, next);
            return new Reservation(true, next);
        }
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private final Map<String, RunStatus> statusByRun = new HashMap<>();
        private final Map<String, List<LlmMessage>> messagesByRun = new HashMap<>();
        private final List<AttemptRecord> attempts = new ArrayList<>();
        private final List<EventRecord> events = new ArrayList<>();
        private int turnNo;

        @Override
        public void createRun(String runId, String userId) {
        }

        @Override
        public void updateRunStatus(String runId, RunStatus status, String error) {
            statusByRun.put(runId, status);
        }

        @Override
        public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
            if (statusByRun.get(runId) != expected) {
                return false;
            }
            statusByRun.put(runId, next);
            return true;
        }

        @Override
        public int nextTurn(String runId) {
            return ++turnNo;
        }

        @Override
        public int currentTurn(String runId) {
            return turnNo;
        }

        @Override
        public String findRunUserId(String runId) {
            return "user-1";
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            return statusByRun.get(runId);
        }

        @Override
        public String appendMessage(String runId, LlmMessage message) {
            messagesByRun.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(message);
            return message.messageId();
        }

        @Override
        public void writeLlmAttempt(String attemptId, String runId, int turnNo, String provider, String model, String status, FinishReason finishReason, Integer promptTokens, Integer completionTokens, Integer totalTokens, String errorJson, String rawDiagnosticJson) {
            attempts.add(new AttemptRecord(attemptId, status));
        }

        @Override
        public void writeAgentEvent(String runId, String eventType, String payloadJson) {
            events.add(new EventRecord(eventType, payloadJson));
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
            return appendMessage(runId, assistant);
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
        }

        @Override
        public List<LlmMessage> loadMessages(String runId) {
            return List.copyOf(messagesByRun.getOrDefault(runId, List.of()));
        }

        @Override
        public List<ToolCall> findToolCallsByRun(String runId) {
            return List.of();
        }
    }

    private static final class FakeRedisToolStore implements RedisToolStore {
        @Override
        public boolean ingestWaiting(String runId, ToolCall call) {
            return true;
        }

        @Override
        public List<StartedTool> schedule(String runId) {
            return List.of();
        }

        @Override
        public boolean complete(StartedTool running, ToolTerminal terminal) {
            return true;
        }

        @Override
        public List<ToolTerminal> reapExpiredLeases(String runId, long nowMillis) {
            return List.of();
        }

        @Override
        public List<ToolTerminal> cancelWaiting(String runId, CancelReason reason) {
            return List.of();
        }

        @Override
        public Optional<ToolTerminal> terminal(String runId, String toolCallId) {
            return Optional.empty();
        }

        @Override
        public Set<String> activeRunIds() {
            return Set.of();
        }

        @Override
        public List<ToolTerminal> abort(String runId, String reason) {
            return List.of();
        }

        @Override
        public boolean abortRequested(String runId) {
            return false;
        }
    }

    private static final class CapturingSink implements AgentEventSink {
        private final List<FinalEvent> finals = new ArrayList<>();
        private final List<ErrorEvent> errors = new ArrayList<>();

        @Override
        public void onTextDelta(TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
        }

        @Override
        public void onFinal(FinalEvent event) {
            finals.add(event);
        }

        @Override
        public void onError(ErrorEvent event) {
            errors.add(event);
        }
    }

    private static final class BudgetedSummaryGenerator implements SummaryGenerator {
        private int providerCalls;

        @Override
        public String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact) {
            context.callObserver().beforeProviderCall();
            providerCalls++;
            List<String> ids = messagesToCompact.stream().map(LlmMessage::messageId).toList();
            return """
                    {"summaryText":"summary","businessFacts":[],"toolFacts":[],"openQuestions":[],"compactedMessageIds":%s}
                    """.formatted(toJsonArray(ids)).trim();
        }

        private String toJsonArray(List<String> ids) {
            List<String> values = new ArrayList<>();
            for (String id : ids) {
                values.add("\"" + id + "\"");
            }
            return "[" + String.join(",", values) + "]";
        }
    }

    private static final class ThrowingSummaryGenerator implements SummaryGenerator {
        @Override
        public String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact) {
            throw new IllegalStateException("summary exploded");
        }
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

    private static final class RecordingCompactionStore implements ContextCompactionStore {
        private final List<ContextCompactionRecord> records = new ArrayList<>();

        @Override
        public String record(ContextCompactionRecord record) {
            records.add(record);
            return "cmp-" + records.size();
        }
    }

    private record AttemptRecord(String attemptId, String status) {
    }

    private record EventRecord(String eventType, String payloadJson) {
    }
}
