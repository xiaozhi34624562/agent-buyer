package com.ai.agent.api;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.trajectory.TrajectoryStore;
import org.springframework.stereotype.Component;

@Component
public final class RunStateMachine {
    private static final int MAX_CAS_RETRIES = 5;

    private final TrajectoryStore trajectoryStore;

    public RunStateMachine(TrajectoryStore trajectoryStore) {
        this.trajectoryStore = trajectoryStore;
    }

    public TransitionResult startRun(String runId) {
        return transition(runId, RunStatus.CREATED, RunStatus.RUNNING, null);
    }

    public TransitionResult startContinuation(String runId) {
        return transition(runId, RunStatus.WAITING_USER_CONFIRMATION, RunStatus.RUNNING, null);
    }

    public TransitionResult restoreWaitingAfterContinuationFailure(String runId) {
        if (trajectoryStore.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.WAITING_USER_CONFIRMATION, null)) {
            return new TransitionResult(RunStatus.WAITING_USER_CONFIRMATION, true);
        }
        return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
    }

    public TransitionResult completeFromRunning(String runId, RunStatus next, String error) {
        return transition(runId, RunStatus.RUNNING, next, error);
    }

    public TransitionResult confirmationTimeout(String runId) {
        return transition(
                runId,
                RunStatus.WAITING_USER_CONFIRMATION,
                RunStatus.TIMEOUT,
                "confirmation timeout"
        );
    }

    public TransitionResult abortIfActive(String runId, String reason) {
        for (int i = 0; i < MAX_CAS_RETRIES; i++) {
            RunStatus current = trajectoryStore.findRunStatus(runId);
            if (isTerminal(current)) {
                return new TransitionResult(current, false);
            }
            if (trajectoryStore.transitionRunStatus(runId, current, RunStatus.CANCELLED, reason)) {
                return new TransitionResult(RunStatus.CANCELLED, true);
            }
        }
        return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
    }

    public void markInitializationFailed(String runId, String error) {
        trajectoryStore.updateRunStatus(runId, RunStatus.FAILED, error);
    }

    public void repairTerminal(String runId, RunStatus status, String error) {
        trajectoryStore.updateRunStatus(runId, status, error);
    }

    public boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.FAILED_RECOVERED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMEOUT;
    }

    private TransitionResult transition(String runId, RunStatus expected, RunStatus next, String error) {
        if (trajectoryStore.transitionRunStatus(runId, expected, next, error)) {
            return new TransitionResult(next, true);
        }
        return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
    }

    public record TransitionResult(RunStatus status, boolean changed) {
    }
}
