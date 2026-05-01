package com.ai.agent;

import com.ai.agent.api.ContinuationLockService;
import com.ai.agent.api.RunAccessManager;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.tool.redis.RedisKeys;
import com.ai.agent.trajectory.TrajectoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunAccessManagerTest {
    FakeTrajectoryStore trajectoryStore;
    FakeStringRedisTemplate redisTemplate;
    RunAccessManager runAccessManager;

    @BeforeEach
    void setUp() {
        List<String> events = new ArrayList<>();
        trajectoryStore = new FakeTrajectoryStore(events);
        redisTemplate = new FakeStringRedisTemplate(events);
        ContinuationLockService continuationLockService = new ContinuationLockService(
                new RedisKeys(new AgentProperties()),
                redisTemplate
        );
        runAccessManager = new RunAccessManager(
                trajectoryStore,
                continuationLockService,
                new FakeRedisToolStore(events)
        );
    }

    @Test
    void assertOwnerAllowsRunOwner() {
        String runId = "run-owner";
        trajectoryStore.owner = "owner";

        runAccessManager.assertOwner(runId, "owner");
    }

    @Test
    void assertOwnerRejectsNonOwnerWithStableAccessException() {
        String runId = "run-private";
        trajectoryStore.owner = "owner";

        assertThatThrownBy(() -> runAccessManager.assertOwner(runId, "intruder"))
                .isInstanceOf(RunAccessManager.RunAccessDeniedException.class)
                .hasMessageContaining(runId);
    }

    @Test
    void assertOwnerRejectsMissingRunWithStableNotFoundException() {
        String runId = "run-missing";
        trajectoryStore.owner = null;

        assertThatThrownBy(() -> runAccessManager.assertOwner(runId, "owner"))
                .isInstanceOf(RunAccessManager.RunNotFoundException.class)
                .hasMessageContaining(runId);
    }

    @Test
    void acquireContinuationChecksOwnerBeforeAcquiringLock() {
        String runId = "run-waiting";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.WAITING_USER_CONFIRMATION;

        RunAccessManager.ContinuationPermit acquired = runAccessManager.acquireContinuation("owner", runId);

        assertThat(acquired.runId()).isEqualTo(runId);
        assertThat(trajectoryStore.status).isEqualTo(RunStatus.RUNNING);
        assertThat(trajectoryStore.events).containsSequence(
                "findOwner:" + runId,
                "acquire:" + runId,
                "findStatus:" + runId,
                "transition:" + runId + ":WAITING_USER_CONFIRMATION->RUNNING"
        );
        assertThat(trajectoryStore.events).doesNotContain("release:" + runId);
    }

    @Test
    void acquireContinuationAllowsPausedRun() {
        String runId = "run-paused";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.PAUSED;

        RunAccessManager.ContinuationPermit acquired = runAccessManager.acquireContinuation("owner", runId);

        assertThat(acquired.runId()).isEqualTo(runId);
        assertThat(trajectoryStore.status).isEqualTo(RunStatus.RUNNING);
        assertThat(trajectoryStore.events).containsSequence(
                "findOwner:" + runId,
                "acquire:" + runId,
                "findStatus:" + runId,
                "clearInterrupt:" + runId,
                "transition:" + runId + ":PAUSED->RUNNING",
                "interruptRequested:" + runId
        );
        assertThat(trajectoryStore.events).doesNotContain("release:" + runId);
    }

    @Test
    void acquireContinuationClearsPausedInterruptBeforeStartingRun() {
        String runId = "run-paused-interrupted";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.PAUSED;
        FakeRedisToolStore toolStore = new FakeRedisToolStore(trajectoryStore.events);
        runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(new AgentProperties()), redisTemplate),
                toolStore
        );

        RunAccessManager.ContinuationPermit acquired = runAccessManager.acquireContinuation("owner", runId);

        assertThat(acquired.runId()).isEqualTo(runId);
        assertThat(toolStore.clearedRunIds).containsExactly(runId);
        assertThat(trajectoryStore.events).containsSequence(
                "findOwner:" + runId,
                "acquire:" + runId,
                "findStatus:" + runId,
                "clearInterrupt:" + runId,
                "transition:" + runId + ":PAUSED->RUNNING"
        );
    }

    @Test
    void acquireContinuationKeepsPausedWhenNewInterruptArrivesAfterClear() {
        String runId = "run-paused-interrupt-race";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.PAUSED;
        FakeRedisToolStore toolStore = new FakeRedisToolStore(trajectoryStore.events);
        toolStore.interruptRequestedAfterClear = true;
        runAccessManager = new RunAccessManager(
                trajectoryStore,
                new ContinuationLockService(new RedisKeys(new AgentProperties()), redisTemplate),
                toolStore
        );

        assertThatThrownBy(() -> runAccessManager.acquireContinuation("owner", runId))
                .isInstanceOf(RunAccessManager.RunContinuationNotAllowedException.class)
                .hasMessageContaining("interrupted");

        assertThat(trajectoryStore.status).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.events).containsSequence(
                "findOwner:" + runId,
                "acquire:" + runId,
                "findStatus:" + runId,
                "clearInterrupt:" + runId,
                "transition:" + runId + ":PAUSED->RUNNING",
                "interruptRequested:" + runId,
                "transition:" + runId + ":RUNNING->PAUSED",
                "release:" + runId
        );
    }

    @Test
    void acquireContinuationReleasesLockWhenStatusCasFails() {
        String runId = "run-cas-race";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.WAITING_USER_CONFIRMATION;
        trajectoryStore.failNextTransition = true;

        assertThatThrownBy(() -> runAccessManager.acquireContinuation("owner", runId))
                .isInstanceOf(RunAccessManager.RunContinuationNotAllowedException.class)
                .hasMessageContaining("WAITING_USER_CONFIRMATION");

        assertThat(trajectoryStore.events).containsSequence(
                "findOwner:" + runId,
                "acquire:" + runId,
                "findStatus:" + runId,
                "transition:" + runId + ":WAITING_USER_CONFIRMATION->RUNNING",
                "findStatus:" + runId,
                "release:" + runId
        );
    }

    @Test
    void restoreWaitingAfterRejectedSubmitUsesCasAndReleasesLock() {
        String runId = "run-rejected";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.WAITING_USER_CONFIRMATION;
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);
        trajectoryStore.events.clear();
        trajectoryStore.status = RunStatus.RUNNING;

        runAccessManager.restoreWaitingAfterRejectedSubmit(permit);

        assertThat(trajectoryStore.status).isEqualTo(RunStatus.WAITING_USER_CONFIRMATION);
        assertThat(trajectoryStore.events).containsExactly(
                "transition:" + runId + ":RUNNING->WAITING_USER_CONFIRMATION",
                "release:" + runId
        );
    }

    @Test
    void restorePausedAfterRejectedSubmitUsesOriginalStatus() {
        String runId = "run-paused-rejected";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.PAUSED;
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);
        trajectoryStore.events.clear();
        trajectoryStore.status = RunStatus.RUNNING;

        runAccessManager.restoreWaitingAfterRejectedSubmit(permit);

        assertThat(permit.previousStatus()).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.status).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.events).containsExactly(
                "transition:" + runId + ":RUNNING->PAUSED",
                "release:" + runId
        );
    }

    @Test
    void restorePausedAfterContinuationStartFailureUsesOriginalStatus() {
        String runId = "run-paused-start-failure";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.PAUSED;
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);
        trajectoryStore.events.clear();
        trajectoryStore.status = RunStatus.RUNNING;

        runAccessManager.restoreWaitingAfterContinuationStartFailure(permit);

        assertThat(trajectoryStore.status).isEqualTo(RunStatus.PAUSED);
        assertThat(trajectoryStore.events).containsExactly(
                "transition:" + runId + ":RUNNING->PAUSED"
        );
    }

    @Test
    void restoreWaitingAfterRejectedSubmitDoesNotOverwriteTerminalStatus() {
        String runId = "run-cancelled";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.WAITING_USER_CONFIRMATION;
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation("owner", runId);
        trajectoryStore.events.clear();
        trajectoryStore.status = RunStatus.CANCELLED;

        runAccessManager.restoreWaitingAfterRejectedSubmit(permit);

        assertThat(trajectoryStore.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(trajectoryStore.events).containsExactly(
                "transition:" + runId + ":RUNNING->WAITING_USER_CONFIRMATION",
                "findStatus:" + runId,
                "release:" + runId
        );
    }

    @Test
    void acquireContinuationRejectsNonOwnerBeforeAcquiringLock() {
        String runId = "run-private";
        trajectoryStore.owner = "owner";

        assertThatThrownBy(() -> runAccessManager.acquireContinuation("intruder", runId))
                .isInstanceOf(RunAccessManager.RunAccessDeniedException.class);

        assertThat(trajectoryStore.events).containsExactly("findOwner:" + runId);
    }

    @Test
    void acquireContinuationReleasesLockWhenStatusIsNotWaitingConfirmation() {
        String runId = "run-running";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.RUNNING;

        assertThatThrownBy(() -> runAccessManager.acquireContinuation("owner", runId))
                .isInstanceOf(RunAccessManager.RunContinuationNotAllowedException.class)
                .hasMessageContaining("WAITING_USER_CONFIRMATION");

        assertThat(trajectoryStore.events).contains("release:" + runId);
    }

    @Test
    void acquireContinuationRejectsOccupiedLockWithStableException() {
        String runId = "run-locked";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.WAITING_USER_CONFIRMATION;
        redisTemplate.lockedRunIds.add(runId);

        assertThatThrownBy(() -> runAccessManager.acquireContinuation("owner", runId))
                .isInstanceOf(RunAccessManager.RunContinuationLockedException.class)
                .hasMessageContaining(runId);

        assertThat(trajectoryStore.events).containsExactly(
                "findOwner:" + runId,
                "acquire:" + runId
        );
    }

    @Test
    void abortIfActiveTransitionsRunningToCancelled() {
        String runId = "run-active-abort";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.RUNNING;

        RunAccessManager.AbortDecision decision = runAccessManager.abortIfActive(runId, "owner");

        assertThat(decision.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(decision.changed()).isTrue();
        assertThat(trajectoryStore.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(trajectoryStore.events).containsExactly(
                "findOwner:" + runId,
                "findStatus:" + runId,
                "transition:" + runId + ":RUNNING->CANCELLED"
        );
    }

    @Test
    void abortIfActiveDoesNotOverwriteTerminalStatus() {
        String runId = "run-terminal-abort";
        trajectoryStore.owner = "owner";
        trajectoryStore.status = RunStatus.SUCCEEDED;

        RunAccessManager.AbortDecision decision = runAccessManager.abortIfActive(runId, "owner");

        assertThat(decision.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(decision.changed()).isFalse();
        assertThat(trajectoryStore.status).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(trajectoryStore.events).containsExactly(
                "findOwner:" + runId,
                "findStatus:" + runId
        );
    }

    private static final class FakeStringRedisTemplate extends StringRedisTemplate {
        private final List<String> events;
        private final List<String> lockedRunIds = new ArrayList<>();

        private FakeStringRedisTemplate(List<String> events) {
            this.events = events;
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
                            events.add("acquire:" + runId);
                            return !lockedRunIds.contains(runId);
                        }
                        return defaultValue(method.getReturnType());
                    }
            );
        }

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            events.add("release:" + runIdFromKey(keys.get(0)));
            return (T) Long.valueOf(1L);
        }

        @Override
        public Boolean delete(String key) {
            events.add("release:" + runIdFromKey(key));
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

    private static final class FakeTrajectoryStore implements TrajectoryStore {
        private final List<String> events;
        private String owner;
        private RunStatus status;
        private boolean failNextTransition;

        private FakeTrajectoryStore(List<String> events) {
            this.events = events;
        }

        @Override
        public String findRunUserId(String runId) {
            events.add("findOwner:" + runId);
            return owner;
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            events.add("findStatus:" + runId);
            return status;
        }

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
            events.add("transition:" + runId + ":" + expected + "->" + next);
            if (failNextTransition) {
                failNextTransition = false;
                return false;
            }
            if (status != expected) {
                return false;
            }
            status = next;
            return true;
        }

        @Override
        public int nextTurn(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int currentTurn(String runId) {
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

    }

    private static final class FakeRedisToolStore implements RedisToolStore {
        private final List<String> events;
        private final List<String> clearedRunIds = new ArrayList<>();
        private boolean interruptRequestedAfterClear;

        private FakeRedisToolStore(List<String> events) {
            this.events = events;
        }

        @Override
        public boolean ingestWaiting(String runId, ToolCall call) {
            return false;
        }

        @Override
        public List<StartedTool> schedule(String runId) {
            return List.of();
        }

        @Override
        public boolean complete(StartedTool running, ToolTerminal terminal) {
            return false;
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

        @Override
        public boolean interruptRequested(String runId) {
            events.add("interruptRequested:" + runId);
            return interruptRequestedAfterClear;
        }

        @Override
        public void clearInterrupt(String runId) {
            clearedRunIds.add(runId);
            events.add("clearInterrupt:" + runId);
        }
    }
}
