package com.ai.agent.api;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunStateMachineTest {
    @Test
    void terminalStatusesAreNotOverwrittenByAbort() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-terminal";
        store.status = RunStatus.SUCCEEDED;

        RunStateMachine.TransitionResult result = stateMachine.abortIfActive(runId, "user_abort");

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.changed()).isFalse();
        assertThat(store.events).containsExactly("findStatus:" + runId);
    }

    @Test
    void activeRunCanBeAbortedWithCas() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-active";
        store.status = RunStatus.RUNNING;

        RunStateMachine.TransitionResult result = stateMachine.abortIfActive(runId, "user_abort");

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.changed()).isTrue();
        assertThat(store.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(store.events).containsExactly(
                "findStatus:" + runId,
                "transition:" + runId + ":RUNNING->CANCELLED"
        );
    }

    @Test
    void restoreWaitingDoesNotOverwriteTerminalRaceWinner() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-race";
        store.status = RunStatus.CANCELLED;

        RunStateMachine.TransitionResult result = stateMachine.restoreWaitingAfterContinuationFailure(runId);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.changed()).isFalse();
        assertThat(store.events).containsExactly(
                "transition:" + runId + ":RUNNING->WAITING_USER_CONFIRMATION",
                "findStatus:" + runId
        );
    }

    @Test
    void confirmationTimeoutUsesTimeoutState() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-confirm-timeout";
        store.status = RunStatus.WAITING_USER_CONFIRMATION;

        RunStateMachine.TransitionResult result = stateMachine.confirmationTimeout(runId);

        assertThat(result.status()).isEqualTo(RunStatus.TIMEOUT);
        assertThat(result.changed()).isTrue();
        assertThat(store.status).isEqualTo(RunStatus.TIMEOUT);
    }

    @Test
    void runningRunCanBePaused() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-pause";
        store.status = RunStatus.RUNNING;

        RunStateMachine.TransitionResult result = stateMachine.pauseFromRunning(runId, "manual_pause");

        assertThat(result.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(result.changed()).isTrue();
        assertThat(store.status).isEqualTo(RunStatus.PAUSED);
        assertThat(store.events).containsExactly(
                "transition:" + runId + ":RUNNING->PAUSED"
        );
    }

    @Test
    void pausedRunCanStartContinuation() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-paused-continuation";
        store.status = RunStatus.PAUSED;

        RunStateMachine.TransitionResult result = stateMachine.startContinuation(runId);

        assertThat(result.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(result.changed()).isTrue();
        assertThat(store.status).isEqualTo(RunStatus.RUNNING);
        assertThat(store.events).containsExactly(
                "findStatus:" + runId,
                "transition:" + runId + ":PAUSED->RUNNING"
        );
    }

    @Test
    void cancelledRunCannotStartContinuation() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);
        String runId = "run-cancelled-continuation";
        store.status = RunStatus.CANCELLED;

        RunStateMachine.TransitionResult result = stateMachine.startContinuation(runId);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.changed()).isFalse();
        assertThat(store.status).isEqualTo(RunStatus.CANCELLED);
        assertThat(store.events).containsExactly(
                "findStatus:" + runId
        );
    }

    @Test
    void pausedStatusIsNotTerminal() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        RunStateMachine stateMachine = new RunStateMachine(store);

        assertThat(stateMachine.isTerminal(RunStatus.PAUSED)).isFalse();
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore {
        private RunStatus status;
        private final List<String> events = new ArrayList<>();

        @Override
        public void createRun(String runId, String userId) {
        }

        @Override
        public void updateRunStatus(String runId, RunStatus status, String error) {
            events.add("update:" + runId + ":" + status);
            this.status = status;
        }

        @Override
        public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
            events.add("transition:" + runId + ":" + expected + "->" + next);
            if (status != expected) {
                return false;
            }
            status = next;
            return true;
        }

        @Override
        public int nextTurn(String runId) {
            return 0;
        }

        @Override
        public int currentTurn(String runId) {
            return 0;
        }

        @Override
        public String findRunUserId(String runId) {
            return null;
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            events.add("findStatus:" + runId);
            return status;
        }

        @Override
        public String appendMessage(String runId, LlmMessage message) {
            return null;
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
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
            return null;
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
        }
    }
}
