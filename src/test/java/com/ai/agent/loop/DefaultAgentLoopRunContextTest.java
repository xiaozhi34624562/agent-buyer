package com.ai.agent.loop;

import com.ai.agent.application.ContinuationLockService;
import com.ai.agent.application.RunAccessManager;
import com.ai.agent.business.user.UserProfile;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.context.PromptAssembler;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmStreamListener;
import com.ai.agent.llm.transcript.TranscriptPairValidator;
import com.ai.agent.support.TestObjectProvider;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.RunEventSinkRegistry;
import com.ai.agent.tool.runtime.ToolResultWaiter;
import com.ai.agent.tool.runtime.ToolRuntime;
import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.tool.security.ConfirmTokenStore;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.RunContextStore;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.dto.UserMessage;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.FinalEvent;
import com.ai.agent.web.sse.TextDeltaEvent;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.ai.agent.web.sse.ToolResultEvent;
import com.ai.agent.web.sse.ToolUseEvent;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class DefaultAgentLoopRunContextTest {
    @Test
    void nullAllowedToolNamesUsesConfiguredDefaultSetInsteadOfAllRegisteredTools() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setMaxTurns(1);
        properties.setDefaultAllowedTools(List.of("query_order"));
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        CapturingProvider provider = new CapturingProvider(trajectoryStore);
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                provider,
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"), new StubTool("cancel_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        loop.run(
                "owner",
                new AgentRunRequest(List.of(new UserMessage("user", "start")), null, null),
                new NoopAgentEventSink()
        );

        assertThat(runContextStore.contextsByRun.get(trajectoryStore.createdRunId).effectiveAllowedTools())
                .containsExactly("query_order");
        assertThat(runContextStore.contextsByRun.get(trajectoryStore.createdRunId).primaryProvider())
                .isEqualTo("deepseek");
        assertThat(runContextStore.contextsByRun.get(trajectoryStore.createdRunId).fallbackProvider())
                .isEqualTo("qwen");
        assertThat(runContextStore.contextsByRun.get(trajectoryStore.createdRunId).providerOptions())
                .isEqualTo("{}");
        assertThat(provider.requests.getFirst().tools())
                .extracting(ToolSchema::name)
                .containsExactly("query_order");
    }

    @Test
    void emptyAllowedToolNamesNarrowsEffectiveToolsToEmptySet() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setMaxTurns(1);
        properties.setDefaultAllowedTools(List.of("query_order", "cancel_order"));
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        CapturingProvider provider = new CapturingProvider(trajectoryStore);
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                provider,
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"), new StubTool("cancel_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        loop.run(
                "owner",
                new AgentRunRequest(List.of(new UserMessage("user", "start")), Set.of(), null),
                new NoopAgentEventSink()
        );

        assertThat(runContextStore.contextsByRun.get(trajectoryStore.createdRunId).effectiveAllowedTools())
                .isEmpty();
        assertThat(provider.requests.getFirst().tools()).isEmpty();
    }

    @Test
    void continuationUsesEffectiveToolsFromCreatedRunInsteadOfAllTools() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setMaxTurns(1);
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        CapturingProvider provider = new CapturingProvider(trajectoryStore);
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                provider,
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"), new StubTool("cancel_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        AgentRunResult created = loop.run(
                "owner",
                new AgentRunRequest(List.of(new UserMessage("user", "start")), Set.of("query_order"), null),
                new NoopAgentEventSink()
        );
        assertThat(created.status()).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);

        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", trajectoryStore.createdRunId);
        loop.continueRun(
                "owner",
                trajectoryStore.createdRunId,
                new UserMessage("user", "continue"),
                new NoopAgentEventSink(),
                permit
        );

        assertThat(provider.requests).hasSize(2);
        assertThat(provider.requests.get(1).tools())
                .extracting(ToolSchema::name)
                .containsExactly("query_order");
    }

    @Test
    void continuationRestoresWaitingStatusWhenRunContextLoadFailsBeforeWorkStarts() {
        AgentProperties properties = new AgentProperties();
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        runContextStore.failLoad = true;
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                new CapturingProvider(trajectoryStore),
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"), new StubTool("cancel_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        String runId = "run-context-missing";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        trajectoryStore.messagesByRun.put(runId, new ArrayList<>());
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);

        Throwable thrown = catchThrowable(() -> loop.continueRun(
                "owner",
                runId,
                new UserMessage("user", "continue"),
                new NoopAgentEventSink(),
                permit
        ));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run context missing");
        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(trajectoryStore.messagesByRun.get(runId)).isEmpty();
    }

    @Test
    void continuationRestoresWaitingStatusWhenRunContextContainsUnknownToolBeforeAppendingUserMessage() {
        AgentProperties properties = new AgentProperties();
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                new CapturingProvider(trajectoryStore),
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        String runId = "run-context-unknown-tool";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        trajectoryStore.messagesByRun.put(runId, new ArrayList<>());
        runContextStore.contextsByRun.put(runId, new RunContext(
                runId,
                List.of("retired_tool"),
                "deepseek-reasoner",
                "deepseek",
                "qwen",
                "{}",
                10,
                null,
                null
        ));
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);

        Throwable thrown = catchThrowable(() -> loop.continueRun(
                "owner",
                runId,
                new UserMessage("user", "continue"),
                new NoopAgentEventSink(),
                permit
        ));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown tool: retired_tool");
        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(trajectoryStore.messagesByRun.get(runId)).isEmpty();
    }

    @Test
    void continuationFailsClosedWhenRunContextProviderSelectionIsMissingBeforeAppendingUserMessage() {
        AgentProperties properties = new AgentProperties();
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                new CapturingProvider(trajectoryStore),
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        String runId = "run-context-missing-provider";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        trajectoryStore.messagesByRun.put(runId, new ArrayList<>());
        runContextStore.contextsByRun.put(runId, new RunContext(
                runId,
                List.of("query_order"),
                "deepseek-reasoner",
                "",
                "qwen",
                "{}",
                10,
                null,
                null
        ));
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);

        Throwable thrown = catchThrowable(() -> loop.continueRun(
                "owner",
                runId,
                new UserMessage("user", "continue"),
                new NoopAgentEventSink(),
                permit
        ));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run context primaryProvider missing");
        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(trajectoryStore.messagesByRun.get(runId)).isEmpty();
    }

    @Test
    void ambiguousConfirmationDoesNotCallProviderAndKeepsRunWaiting() {
        AgentProperties properties = new AgentProperties();
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        CapturingProvider provider = new CapturingProvider(trajectoryStore);
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                provider,
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        String runId = "run-ambiguous-confirm";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        trajectoryStore.messagesByRun.put(runId, new ArrayList<>());
        runContextStore.contextsByRun.put(runId, new RunContext(
                runId,
                List.of("query_order"),
                "deepseek-reasoner",
                "deepseek",
                "qwen",
                "{}",
                10,
                null,
                null
        ));
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);

        AgentRunResult result = loop.continueRun(
                "owner",
                runId,
                new UserMessage("user", "我再想一下"),
                new NoopAgentEventSink(),
                permit
        );

        assertThat(result.status()).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(provider.requests).isEmpty();
    }

    @Test
    void runInitializationMarksRunFailedWhenRunContextCreateFails() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setMaxTurns(1);
        properties.setDefaultAllowedTools(List.of("query_order"));
        FakeTrajectoryStore trajectoryStore = new FakeTrajectoryStore();
        FakeRunContextStore runContextStore = new FakeRunContextStore();
        runContextStore.failCreate = true;
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(properties), redisTemplate),
                new FakeRedisToolStore()
        );
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                new CapturingProvider(trajectoryStore),
                new TranscriptPairValidator(),
                new ToolRegistry(List.of(new StubTool("query_order"))),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                trajectoryStore,
                trajectoryStore,
                runContextStore,
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(properties, redisTemplate, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        Throwable thrown = catchThrowable(() -> loop.run(
                "owner",
                new AgentRunRequest(List.of(new UserMessage("user", "start")), null, null),
                new NoopAgentEventSink()
        ));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run context create failed");
        assertThat(trajectoryStore.statusByRun.get(trajectoryStore.createdRunId)).isEqualTo(RunStatus.FAILED);
        assertThat(trajectoryStore.messagesByRun.get(trajectoryStore.createdRunId)).isEmpty();
    }

    private record StubTool(String name) implements Tool {
        @Override
        public ToolSchema schema() {
            return new ToolSchema(
                    name,
                    "stub",
                    "{}",
                    true,
                    true,
                    Duration.ofSeconds(5),
                    4096,
                    List.of()
            );
        }
    }

    private static final class CapturingProvider implements LlmProviderAdapter {
        private final FakeTrajectoryStore trajectoryStore;
        private final List<LlmChatRequest> requests = new ArrayList<>();

        private CapturingProvider(FakeTrajectoryStore trajectoryStore) {
            this.trajectoryStore = trajectoryStore;
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
            requests.add(request);
            if (requests.size() == 1) {
                trajectoryStore.statusByRun.put(request.runId(), RunStatus.WAITING_USER_CONFIRMATION);
            }
            return new LlmStreamResult("ok", List.of(), FinishReason.STOP, null, null);
        }
    }

    private static final class FakeRunContextStore implements RunContextStore {
        private final Map<String, RunContext> contextsByRun = new HashMap<>();
        private boolean failCreate;
        private boolean failLoad;

        @Override
        public void create(RunContext context) {
            if (failCreate) {
                throw new IllegalStateException("run context create failed");
            }
            contextsByRun.put(context.runId(), context);
        }

        @Override
        public RunContext load(String runId) {
            if (failLoad) {
                throw new IllegalStateException("run context missing");
            }
            return contextsByRun.get(runId);
        }
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private final Map<String, String> ownerByRun = new HashMap<>();
        private final Map<String, RunStatus> statusByRun = new HashMap<>();
        private final Map<String, List<LlmMessage>> messagesByRun = new HashMap<>();
        private String createdRunId;
        private int turnNo;

        @Override
        public void createRun(String runId, String userId) {
            createdRunId = runId;
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
            // Test fake records requests at the provider boundary.
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
                        if ("setIfAbsent".equals(method.getName()) && args.length == 3 && args[2] instanceof Duration) {
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
