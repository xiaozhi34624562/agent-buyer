package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.LlmStreamListener;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTurnOrchestratorBudgetTest {
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

    private static AgentTurnOrchestrator orchestrator(
            AgentProperties properties,
            FakeTrajectoryStore trajectoryStore,
            LlmProviderAdapter provider,
            RunLlmCallBudgetStore budgetStore
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new AgentTurnOrchestrator(
                properties,
                new TranscriptPairValidator(),
                new LlmAttemptService(new LlmProviderAdapterRegistry(List.of(provider)), trajectoryStore, objectMapper),
                new ToolCallCoordinator(
                        properties,
                        new ToolRegistry(List.of()),
                        (ToolRuntime) (runId, call) -> {
                        },
                        new FakeRedisToolStore(),
                        new ToolResultWaiter(new FakeRedisToolStore()),
                        trajectoryStore,
                        trajectoryStore,
                        new ToolResultCloser(trajectoryStore, trajectoryStore),
                        objectMapper
                ),
                trajectoryStore,
                trajectoryStore,
                new RunStateMachine(trajectoryStore),
                new AgentExecutionBudget(properties, budgetStore)
        );
    }

    private static RunContext runContext(String runId) {
        return new RunContext(
                runId,
                List.of(),
                "deepseek-reasoner",
                "deepseek",
                "qwen",
                "{}",
                10,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
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
        }
    }

    private record AttemptRecord(String attemptId, String status) {
    }

    private record EventRecord(String eventType, String payloadJson) {
    }
}
