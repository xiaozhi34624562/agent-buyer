package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmStreamListener;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.PromptAssembler;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.support.TestObjectProvider;
import com.ai.agent.subagent.ChildReleaseReason;
import com.ai.agent.subagent.ChildRunRef;
import com.ai.agent.subagent.ChildRunRegistry;
import com.ai.agent.subagent.ParentLinkStatus;
import com.ai.agent.subagent.ReserveChildCommand;
import com.ai.agent.subagent.ReserveChildResult;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.ConfirmTokenStore;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.redis.RedisKeys;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryQueryService;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectorySnapshot;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.business.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerAccessTest {
    MockMvc mockMvc;
    FakeAgentLoop agentLoop;
    FakeTrajectoryStore trajectoryStore;
    CountingExecutorService agentExecutor;
    FakeRedisToolStore redisToolStore;
    FakeStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        agentLoop = new FakeAgentLoop();
        trajectoryStore = new FakeTrajectoryStore();
        agentExecutor = new CountingExecutorService();
        redisToolStore = new FakeRedisToolStore();
        redisTemplate = new FakeStringRedisTemplate();
        ContinuationLockService continuationLockService = new ContinuationLockService(
                new RedisKeys(new AgentProperties()),
                redisTemplate
        );
        RunAccessManager runAccessManager = new RunAccessManager(
                trajectoryStore,
                continuationLockService,
                redisToolStore
        );
        AgentRunApplicationService applicationService = new AgentRunApplicationService(
                agentLoop,
                new RunAdmissionController(),
                null,
                new AgentRequestPolicy(new AgentProperties()),
                runAccessManager,
                continuationLockService,
                redisToolStore,
                new ToolResultCloser(trajectoryStore, trajectoryStore),
                new TrajectoryQueryService(trajectoryStore, new ObjectMapper()),
                interruptService(runAccessManager)
        );
        AgentController controller = new AgentController(
                applicationService,
                agentExecutor,
                new NoopScheduledExecutorService(),
                new SseMetrics(new SimpleMeterRegistry()),
                null
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private RunInterruptService interruptService(RunAccessManager runAccessManager) {
        return new RunInterruptService(
                new AgentProperties(),
                runAccessManager,
                new RunStateMachine(trajectoryStore),
                redisToolStore,
                new ToolResultCloser(trajectoryStore, trajectoryStore),
                new RunEventSinkRegistry(),
                new EmptyChildRunRegistry(),
                trajectoryStore
        );
    }

    @Test
    void missingUserHeaderIsRejectedBeforeTrajectoryIsLoaded() throws Exception {
        String runId = "run-owner";
        trajectoryStore.ownerByRun.put(runId, "owner");

        mockMvc.perform(get("/api/agent/runs/{runId}", runId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("authenticated user is required"));

        assertThat(trajectoryStore.loadTrajectoryCount).isZero();
    }

    @Test
    void blankUserHeaderIsRejectedBeforeMutatingRunState() throws Exception {
        String runId = "run-owner";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.RUNNING);

        mockMvc.perform(post("/api/agent/runs/{runId}/abort", runId)
                        .header("X-User-Id", " "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("authenticated user is required"));

        assertThat(redisToolStore.abortCount).isZero();
        assertThat(trajectoryStore.updateRunStatusCount).isZero();
    }

    @Test
    void ownerCanQueryTrajectory() throws Exception {
        String runId = "run-owner";
        trajectoryStore.ownerByRun.put(runId, "owner");

        mockMvc.perform(get("/api/agent/runs/{runId}", runId)
                        .header("X-User-Id", "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.runId").value(runId));
    }

    @Test
    void nonOwnerQueryIsRejectedBeforeTrajectoryIsLoaded() throws Exception {
        String runId = "run-private";
        trajectoryStore.ownerByRun.put(runId, "owner");

        mockMvc.perform(get("/api/agent/runs/{runId}", runId)
                        .header("X-User-Id", "intruder"))
                .andExpect(status().isForbidden());

        assertThat(trajectoryStore.loadTrajectoryCount).isZero();
    }

    @Test
    void missingRunQueryReturnsNotFoundBeforeTrajectoryIsLoaded() throws Exception {
        String runId = "run-missing";

        mockMvc.perform(get("/api/agent/runs/{runId}", runId)
                        .header("X-User-Id", "owner"))
                .andExpect(status().isNotFound());

        assertThat(trajectoryStore.loadTrajectoryCount).isZero();
    }

    @Test
    void nonOwnerAbortIsRejectedBeforeMutatingRunState() throws Exception {
        String runId = "run-private";
        trajectoryStore.ownerByRun.put(runId, "owner");

        mockMvc.perform(post("/api/agent/runs/{runId}/abort", runId)
                        .header("X-User-Id", "intruder"))
                .andExpect(status().isForbidden());

        assertThat(redisToolStore.abortCount).isZero();
        assertThat(trajectoryStore.updateRunStatusCount).isZero();
    }

    @Test
    void missingRunAbortReturnsNotFoundBeforeMutatingRunState() throws Exception {
        String runId = "run-missing";

        mockMvc.perform(post("/api/agent/runs/{runId}/abort", runId)
                        .header("X-User-Id", "owner"))
                .andExpect(status().isNotFound());

        assertThat(redisToolStore.abortCount).isZero();
        assertThat(trajectoryStore.updateRunStatusCount).isZero();
    }

    @Test
    void ownerAbortRunningRunTransitionsToCancelledAndSignalsRedis() throws Exception {
        String runId = "run-active-abort";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.RUNNING);

        mockMvc.perform(post("/api/agent/runs/{runId}/abort", runId)
                        .header("X-User-Id", "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.CANCELLED);
        assertThat(trajectoryStore.transitionEvents).containsExactly(
                "transition:" + runId + ":RUNNING->CANCELLED"
        );
        assertThat(redisToolStore.abortCount).isEqualTo(1);
    }

    @Test
    void lateAbortDoesNotOverwriteSucceededRun() throws Exception {
        String runId = "run-already-succeeded";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.SUCCEEDED);

        mockMvc.perform(post("/api/agent/runs/{runId}/abort", runId)
                        .header("X-User-Id", "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(trajectoryStore.transitionEvents).isEmpty();
        assertThat(redisToolStore.abortCount).isZero();
        assertThat(trajectoryStore.updateRunStatusCount).isZero();
    }

    @Test
    void repeatedAbortOnCancelledRunDoesNotSignalRedisAgain() throws Exception {
        String runId = "run-already-cancelled";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.CANCELLED);

        mockMvc.perform(post("/api/agent/runs/{runId}/abort", runId)
                        .header("X-User-Id", "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.CANCELLED);
        assertThat(trajectoryStore.transitionEvents).isEmpty();
        assertThat(redisToolStore.abortCount).isZero();
    }

    @Test
    void nonOwnerContinuationIsRejectedBeforeSubmittingAgentWork() throws Exception {
        String runId = "run-private";
        trajectoryStore.ownerByRun.put(runId, "owner");

        mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                        .header("X-User-Id", "intruder")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":{"role":"user","content":"确认取消"}}
                                """))
                .andExpect(status().isForbidden());

        assertThat(agentExecutor.executeCount).isZero();
        assertThat(agentLoop.continueRunCount).isZero();
    }

    @Test
    void continuationWrongStatusIsRejectedBeforeSubmittingAgentWork() throws Exception {
        String runId = "run-running";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.RUNNING);

        mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                        .header("X-User-Id", "owner")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":{"role":"user","content":"确认取消"}}
                                """))
                .andExpect(status().isConflict());

        assertThat(agentExecutor.executeCount).isZero();
    }

    @Test
    void continuationLockedIsRejectedBeforeSubmittingAgentWork() throws Exception {
        String runId = "run-locked";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        redisTemplate.putLock(runId, "existing-lock");

        mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                        .header("X-User-Id", "owner")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":{"role":"user","content":"确认取消"}}
                                """))
                .andExpect(status().isLocked());

        assertThat(agentExecutor.executeCount).isZero();
        assertThat(agentLoop.continueRunCount).isZero();
    }

    @Test
    void invalidCreateRunRequestReturnsBadRequestBeforeSubmittingAgentWork() throws Exception {
        mockMvc.perform(post("/api/agent/runs")
                        .header("X-User-Id", "owner")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"hello"}],"llmParams":{"temperature":-0.1}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("temperature is out of range"));

        assertThat(agentExecutor.executeCount).isZero();
    }

    @Test
    void continuationReleasesAcquiredLockWhenExecutorRejectsWork() {
        String runId = "run-rejected";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        agentExecutor.reject = true;

        Throwable thrown = catchThrowable(() -> mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                .header("X-User-Id", "owner")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"message":{"role":"user","content":"确认取消"}}
                        """)));

        assertThat(thrown).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(redisTemplate.hasLock(runId)).isFalse();
        assertThat(redisTemplate.releaseCountByRun.get(runId)).isEqualTo(1);
        assertThat(agentLoop.continueRunCount).isZero();
    }

    @Test
    void continuationCasFailureReturnsConflictAndDoesNotSubmitWork() throws Exception {
        String runId = "run-cas-failed";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        trajectoryStore.failNextTransition = true;

        mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                        .header("X-User-Id", "owner")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":{"role":"user","content":"确认取消"}}
                                """))
                .andExpect(status().isConflict());

        assertThat(agentExecutor.executeCount).isZero();
        assertThat(agentLoop.continueRunCount).isZero();
        assertThat(redisTemplate.hasLock(runId)).isFalse();
        assertThat(redisTemplate.releaseCountByRun.get(runId)).isEqualTo(1);
    }

    @Test
    void rejectedContinuationSubmitRestoresWaitingOnlyWhenRunIsStillRunning() {
        String runId = "run-reject-restore";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        agentExecutor.reject = true;

        Throwable thrown = catchThrowable(() -> mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                .header("X-User-Id", "owner")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"message":{"role":"user","content":"确认取消"}}
                        """)));

        assertThat(thrown).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(trajectoryStore.transitionEvents).containsExactly(
                "transition:" + runId + ":WAITING_USER_CONFIRMATION->RUNNING",
                "transition:" + runId + ":RUNNING->WAITING_USER_CONFIRMATION"
        );
        assertThat(redisTemplate.hasLock(runId)).isFalse();
        assertThat(redisTemplate.releaseCountByRun.get(runId)).isEqualTo(1);
    }

    @Test
    void rejectedContinuationSubmitDoesNotRestoreWaitingAfterTerminalStatusWinsRace() {
        String runId = "run-reject-terminal";
        trajectoryStore.ownerByRun.put(runId, "owner");
        trajectoryStore.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        agentExecutor.reject = true;
        agentExecutor.beforeReject = () -> trajectoryStore.statusByRun.put(runId, RunStatus.CANCELLED);

        Throwable thrown = catchThrowable(() -> mockMvc.perform(post("/api/agent/runs/{runId}/messages", runId)
                .header("X-User-Id", "owner")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                        {"message":{"role":"user","content":"确认取消"}}
                        """)));

        assertThat(thrown).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(trajectoryStore.statusByRun.get(runId)).isEqualTo(RunStatus.CANCELLED);
        assertThat(trajectoryStore.transitionEvents).containsExactly(
                "transition:" + runId + ":WAITING_USER_CONFIRMATION->RUNNING",
                "transition:" + runId + ":RUNNING->WAITING_USER_CONFIRMATION"
        );
        assertThat(redisTemplate.hasLock(runId)).isFalse();
    }

    @Test
    void lockedContinuationExecutorDoesNotAppendOrCallLlmWhenRunWasCancelledWhileQueued() {
        String runId = "run-cancelled-before-executor";
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        store.ownerByRun.put(runId, "owner");
        store.statusByRun.put(runId, RunStatus.WAITING_USER_CONFIRMATION);
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();
        ContinuationLockService continuationLockService = new ContinuationLockService(
                new RedisKeys(new AgentProperties()),
                redis
        );
        RunAccessManager runAccessManager = new RunAccessManager(
                store,
                continuationLockService,
                new FakeRedisToolStore()
        );
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);
        store.statusByRun.put(runId, RunStatus.CANCELLED);
        CountingLlmProvider provider = new CountingLlmProvider();
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                new AgentProperties(),
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                provider,
                new TranscriptPairValidator(),
                new ToolRegistry(List.of()),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), new AgentProperties(), TestObjectProvider.empty()),
                store,
                store,
                new NoopRunContextStore(),
                new RunEventSinkRegistry(),
                runAccessManager,
                new ConfirmTokenStore(new AgentProperties(), redis, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        loop.continueRun(
                "owner",
                runId,
                new UserMessage("user", "确认取消"),
                new NoopAgentEventSink(),
                permit
        );

        assertThat(store.appendedMessages).isEmpty();
        assertThat(provider.streamChatCount).isZero();
        assertThat(store.statusByRun.get(runId)).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void agentLoopDoesNotExposeForgeableContinuationLockOverload() {
        boolean exposesRawLock = Arrays.stream(AgentLoop.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(ContinuationLockService.Lock.class::equals);

        assertThat(exposesRawLock).isFalse();
    }

    @Test
    void finalSuccessDoesNotOverwriteCancelledAfterLlmReturns() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        FakeStringRedisTemplate redis = new FakeStringRedisTemplate();
        CountingLlmProvider provider = new CountingLlmProvider();
        provider.beforeReturn = request -> store.statusByRun.put(request.runId(), RunStatus.CANCELLED);
        AgentProperties properties = new AgentProperties();
        properties.setDefaultAllowedTools(List.of());
        DefaultAgentLoop loop = AgentLoopTestFactory.create(
                properties,
                new PromptAssembler(userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")),
                provider,
                new TranscriptPairValidator(),
                new ToolRegistry(List.of()),
                (ToolRuntime) (ignoredRunId, call) -> {
                },
                new FakeRedisToolStore(),
                new ToolResultWaiter(new FakeRedisToolStore(), properties, TestObjectProvider.empty()),
                store,
                store,
                new NoopRunContextStore(),
                new RunEventSinkRegistry(),
                new RunAccessManager(
                        store,
                        new ContinuationLockService(new RedisKeys(properties), redis),
                        new FakeRedisToolStore()
                ),
                new ConfirmTokenStore(properties, redis, new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.fasterxml.jackson.databind.ObjectMapper()
        );

        AgentRunResult result = loop.run(
                "owner",
                new AgentRunRequest(List.of(new UserMessage("user", "hello")), null, null),
                new NoopAgentEventSink()
        );

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(store.statusByRun.get(store.createdRunId)).isEqualTo(RunStatus.CANCELLED);
        assertThat(store.appendedMessages).noneMatch(message -> "ok".equals(message.content()));
    }

    private static final class FakeAgentLoop implements AgentLoop {
        private int continueRunCount;

        @Override
        public AgentRunResult run(String userId, AgentRunRequest request, AgentEventSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentRunResult continueRun(String userId, String runId, UserMessage message, AgentEventSink sink) {
            continueRunCount++;
            return new AgentRunResult(runId, RunStatus.SUCCEEDED, "ok");
        }

        @Override
        public AgentRunResult continueRun(
                String userId,
                String runId,
                UserMessage message,
                AgentEventSink sink,
                RunAccessManager.ContinuationPermit permit
        ) {
            continueRunCount++;
            return new AgentRunResult(runId, RunStatus.SUCCEEDED, "ok");
        }
    }

    private static final class CountingExecutorService extends AbstractExecutorService {
        private int executeCount;
        private boolean shutdown;
        private boolean reject;
        private Runnable beforeReject;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            executeCount++;
            if (reject) {
                if (beforeReject != null) {
                    beforeReject.run();
                }
                throw new RejectedExecutionException("rejected");
            }
            command.run();
        }
    }

    private static final class NoopScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return new CompletedScheduledFuture<>(null);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            try {
                return new CompletedScheduledFuture<>(callable.call());
            } catch (Exception e) {
                return new FailedScheduledFuture<>(e);
            }
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            return new CompletedScheduledFuture<>(null);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return new CompletedScheduledFuture<>(null);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static class CompletedScheduledFuture<V> implements ScheduledFuture<V> {
        private final V value;

        private CompletedScheduledFuture(V value) {
            this.value = value;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public V get() throws ExecutionException {
            return value;
        }

        @Override
        public V get(long timeout, TimeUnit unit) {
            return value;
        }
    }

    private static final class FailedScheduledFuture<V> extends CompletedScheduledFuture<V> {
        private final Exception exception;

        private FailedScheduledFuture(Exception exception) {
            super(null);
            this.exception = exception;
        }

        @Override
        public V get() throws ExecutionException {
            throw new ExecutionException(exception);
        }
    }

    private static final class FakeStringRedisTemplate extends StringRedisTemplate {
        private final Map<String, String> locksByRun = new HashMap<>();
        private final Map<String, Integer> releaseCountByRun = new HashMap<>();

        void putLock(String runId, String value) {
            locksByRun.put(runId, value);
        }

        boolean hasLock(String runId) {
            return locksByRun.containsKey(runId);
        }

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
            releaseCountByRun.merge(runId, 1, Integer::sum);
            if (args.length > 0 && args[0].equals(locksByRun.get(runId))) {
                locksByRun.remove(runId);
                return (T) Long.valueOf(1L);
            }
            return (T) Long.valueOf(0L);
        }

        @Override
        public Boolean delete(String key) {
            locksByRun.remove(runIdFromKey(key));
            return true;
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
        private int abortCount;

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
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ToolTerminal> abort(String runId, String reason) {
            abortCount++;
            return List.of();
        }

        @Override
        public boolean abortRequested(String runId) {
            return abortCount > 0;
        }
    }

    private static final class EmptyChildRunRegistry implements ChildRunRegistry {
        @Override
        public ReserveChildResult reserve(ReserveChildCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean release(
                String parentRunId,
                String childRunId,
                ChildReleaseReason reason,
                ParentLinkStatus parentLinkStatus
        ) {
            return false;
        }

        @Override
        public List<ChildRunRef> findActiveChildren(String parentRunId) {
            return List.of();
        }
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private final java.util.Map<String, String> ownerByRun = new java.util.HashMap<>();
        private final java.util.Map<String, RunStatus> statusByRun = new java.util.HashMap<>();
        private final List<LlmMessage> appendedMessages = new java.util.ArrayList<>();
        private final List<String> transitionEvents = new java.util.ArrayList<>();
        private String createdRunId;
        private int turnNo;
        private int loadTrajectoryCount;
        private int updateRunStatusCount;
        private boolean failNextTransition;

        @Override
        public String findRunUserId(String runId) {
            return ownerByRun.get(runId);
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            return statusByRun.get(runId);
        }

        @Override
        public TrajectorySnapshot loadTrajectorySnapshot(String runId) {
            loadTrajectoryCount++;
            AgentRunEntity run = new AgentRunEntity();
            run.setRunId(runId);
            run.setUserId(ownerByRun.get(runId));
            run.setStatus(statusByRun.getOrDefault(runId, RunStatus.RUNNING).name());
            run.setTurnNo(turnNo);
            return new TrajectorySnapshot(run, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        @Override
        public void updateRunStatus(String runId, RunStatus status, String error) {
            updateRunStatusCount++;
            statusByRun.put(runId, status);
        }

        @Override
        public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
            transitionEvents.add("transition:" + runId + ":" + expected + "->" + next);
            if (failNextTransition) {
                failNextTransition = false;
                return false;
            }
            if (statusByRun.get(runId) != expected) {
                return false;
            }
            statusByRun.put(runId, next);
            return true;
        }

        @Override
        public void createRun(String runId, String userId) {
            createdRunId = runId;
            ownerByRun.put(runId, userId);
            statusByRun.put(runId, RunStatus.CREATED);
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
        public String appendMessage(String runId, LlmMessage message) {
            appendedMessages.add(message);
            return message.messageId();
        }

        @Override
        public List<LlmMessage> loadMessages(String runId) {
            return List.copyOf(appendedMessages);
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
            // Test fake only records that the call was accepted.
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

    private static final class CountingLlmProvider implements LlmProviderAdapter {
        private int streamChatCount;
        private java.util.function.Consumer<LlmChatRequest> beforeReturn;

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
            streamChatCount++;
            if (beforeReturn != null) {
                beforeReturn.accept(request);
            }
            return new LlmStreamResult("ok", List.of(), FinishReason.STOP, null, null);
        }
    }

    private static final class NoopRunContextStore implements RunContextStore {
        @Override
        public void create(RunContext context) {
        }

        @Override
        public RunContext load(String runId) {
            throw new UnsupportedOperationException();
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
