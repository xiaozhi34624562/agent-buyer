package com.ai.agent.api;

import com.ai.agent.business.UserProfile;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.ContextViewBuilder;
import com.ai.agent.llm.DeterministicSummaryGenerator;
import com.ai.agent.llm.LargeResultSpiller;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.LlmStreamListener;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.MicroCompactor;
import com.ai.agent.llm.PromptAssembler;
import com.ai.agent.llm.ProviderCallException;
import com.ai.agent.llm.ProviderErrorType;
import com.ai.agent.llm.SummaryCompactor;
import com.ai.agent.llm.TokenEstimator;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.support.TestObjectProvider;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.ConfirmTokenStore;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.redis.RedisKeys;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class V20ProviderSelectionIntegrationTest {
    @Test
    void continuationUsesRunContextProviderAndModelInsteadOfRequestOrConfiguredDefaults() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setMaxTurns(3);
        properties.setDefaultAllowedTools(List.of());
        ObjectMapper objectMapper = new ObjectMapper();
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        InMemoryRunContextStore runContextStore = new InMemoryRunContextStore();
        CapturingProvider qwen = new CapturingProvider("qwen", "qwen-default");
        FailingProvider deepseek = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "deepseek must not be selected")
        );
        String runId = "run-provider-reuse";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.PAUSED);
        trajectoryStore.messagesByRun.put(runId, new ArrayList<>(List.of(
                LlmMessage.system("msg-system", "system"),
                LlmMessage.user("msg-original", "original request")
        )));
        runContextStore.contextsByRun.put(runId, new RunContext(
                runId,
                List.of(),
                "qwen-max-from-run-context",
                "qwen",
                "deepseek",
                "{}",
                3,
                LocalDateTime.now(),
                LocalDateTime.now()
        ));
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = new DefaultAgentLoop(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                new ToolRegistry(List.of()),
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                turnOrchestrator(properties, trajectoryStore, objectMapper, qwen, deepseek),
                new ConfirmationIntentService(),
                new ConfirmTokenStore(properties, redisTemplate, objectMapper)
        );

        AgentRunResult result = loop.continueRun(
                "owner",
                runId,
                new UserMessage("user", "continue"),
                new NoopAgentEventSink()
        );

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(qwen.requests).singleElement().satisfies(request -> {
            assertThat(request.runId()).isEqualTo(runId);
            assertThat(request.model()).isEqualTo("qwen-max-from-run-context");
        });
        assertThat(deepseek.requests).isEmpty();
        assertThat(trajectoryStore.attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.provider()).isEqualTo("qwen");
            assertThat(attempt.model()).isEqualTo("qwen-max-from-run-context");
            assertThat(attempt.status()).isEqualTo("SUCCEEDED");
        });
    }

    private static AgentTurnOrchestrator turnOrchestrator(
            AgentProperties properties,
            RecordingTrajectoryStore trajectoryStore,
            ObjectMapper objectMapper,
            LlmProviderAdapter... providers
    ) {
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        RedisToolStore redisToolStore = new FakeRedisToolStore();
        return new AgentTurnOrchestrator(
                properties,
                new ContextViewBuilder(
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
                ),
                new LlmAttemptService(
                        new LlmProviderAdapterRegistry(List.of(providers)),
                        trajectoryStore,
                        objectMapper,
                        record -> null
                ),
                new ToolCallCoordinator(
                        properties,
                        toolRegistry,
                        (ToolRuntime) (ignoredRunId, call) -> {
                        },
                        redisToolStore,
                        new ToolResultWaiter(redisToolStore, properties, TestObjectProvider.empty()),
                        trajectoryStore,
                        trajectoryStore,
                        new ToolResultCloser(trajectoryStore, trajectoryStore, TestObjectProvider.empty()),
                        objectMapper
                ),
                trajectoryStore,
                trajectoryStore,
                new RunStateMachine(trajectoryStore),
                new AgentExecutionBudget(properties, new InMemoryRunLlmCallBudgetStore())
        );
    }

    private record AttemptRecord(String provider, String model, String status) {
    }

    private static final class CapturingProvider implements LlmProviderAdapter {
        private final String providerName;
        private final String defaultModel;
        private final List<LlmChatRequest> requests = new ArrayList<>();

        private CapturingProvider(String providerName, String defaultModel) {
            this.providerName = providerName;
            this.defaultModel = defaultModel;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String defaultModel() {
            return defaultModel;
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            requests.add(request);
            return new LlmStreamResult("qwen answer", List.of(), FinishReason.STOP, null, null);
        }
    }

    private static final class FailingProvider implements LlmProviderAdapter {
        private final String providerName;
        private final RuntimeException failure;
        private final List<LlmChatRequest> requests = new ArrayList<>();

        private FailingProvider(String providerName, RuntimeException failure) {
            this.providerName = providerName;
            this.failure = failure;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String defaultModel() {
            return providerName + "-default";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            requests.add(request);
            throw failure;
        }
    }

    private static final class InMemoryRunContextStore implements RunContextStore {
        private final Map<String, RunContext> contextsByRun = new HashMap<>();

        @Override
        public void create(RunContext context) {
            contextsByRun.put(context.runId(), context);
        }

        @Override
        public RunContext load(String runId) {
            return contextsByRun.get(runId);
        }
    }

    private static final class InMemoryRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
        private final Map<String, Long> countsByRun = new HashMap<>();

        @Override
        public Reservation reserveRunCall(String runId, int limit) {
            long used = countsByRun.getOrDefault(runId, 0L);
            if (used >= limit) {
                return new Reservation(false, used);
            }
            long next = used + 1;
            countsByRun.put(runId, next);
            return new Reservation(true, next);
        }
    }

    private static final class RecordingTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private final Map<String, String> ownerByRun = new HashMap<>();
        private final Map<String, RunStatus> statusByRun = new HashMap<>();
        private final Map<String, List<LlmMessage>> messagesByRun = new HashMap<>();
        private final List<AttemptRecord> attempts = new ArrayList<>();
        private int turnNo;

        @Override
        public void createRun(String runId, String userId) {
            ownerByRun.put(runId, userId);
            statusByRun.put(runId, RunStatus.CREATED);
            messagesByRun.put(runId, new ArrayList<>());
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
            return ownerByRun.get(runId);
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
        public List<LlmMessage> loadMessages(String runId) {
            return List.copyOf(messagesByRun.getOrDefault(runId, List.of()));
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
            attempts.add(new AttemptRecord(provider, model, status));
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
        public List<ToolCall> findToolCallsByRun(String runId) {
            return List.of();
        }
    }

    private static final class FakeStringRedisTemplate extends StringRedisTemplate {
        private final Map<String, String> locksByRun = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public ValueOperations<String, String> opsForValue() {
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    (proxy, method, args) -> {
                        if ("setIfAbsent".equals(method.getName()) && args.length == 3 && args[2] instanceof java.time.Duration) {
                            String runId = runIdFromKey((String) args[0]);
                            if (locksByRun.containsKey(runId)) {
                                return false;
                            }
                            locksByRun.put(runId, (String) args[1]);
                            return true;
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            String runId = runIdFromKey(keys.get(0));
            if (args.length > 0 && args[0].equals(locksByRun.get(runId))) {
                locksByRun.remove(runId);
                return (T) Long.valueOf(1L);
            }
            return (T) Long.valueOf(0L);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == int.class) {
                return 0;
            }
            return null;
        }

        private static String runIdFromKey(String key) {
            int start = key.indexOf("{run:");
            int end = key.indexOf("}", start);
            if (start < 0 || end < 0) {
                return key;
            }
            return key.substring(start + 5, end);
        }
    }

    private static final class FakeRedisToolStore implements RedisToolStore {
        @Override
        public boolean ingestWaiting(String runId, ToolCall call) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StartedTool> schedule(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean complete(StartedTool running, ToolTerminal terminal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolTerminal> reapExpiredLeases(String runId, long nowMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolTerminal> cancelWaiting(String runId, CancelReason reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ToolTerminal> terminal(String runId, String toolCallId) {
            throw new UnsupportedOperationException();
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

    private static final class NoopAgentEventSink implements AgentEventSink {
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
        }

        @Override
        public void onError(ErrorEvent event) {
        }
    }
}
