package com.ai.agent.application;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.trajectory.port.TrajectoryStore;
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
        RunStatus current = trajectoryStore.findRunStatus(runId);
        return startContinuation(runId, current);
    }

    public TransitionResult startContinuation(String runId, RunStatus current) {
        if (current == RunStatus.WAITING_USER_CONFIRMATION || current == RunStatus.PAUSED) {
            return transition(runId, current, RunStatus.RUNNING, null);
        }
        return new TransitionResult(current, false);
    }

    public TransitionResult restoreWaitingAfterContinuationFailure(String runId) {
        return restoreAfterContinuationFailure(runId, RunStatus.WAITING_USER_CONFIRMATION);
    }

    public TransitionResult restoreAfterContinuationFailure(String runId, RunStatus previousStatus) {
        if (previousStatus != RunStatus.WAITING_USER_CONFIRMATION && previousStatus != RunStatus.PAUSED) {
            return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
        }
        if (trajectoryStore.transitionRunStatus(runId, RunStatus.RUNNING, previousStatus, null)) {
            return new TransitionResult(previousStatus, true);
        }
        return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
    }

    public TransitionResult completeFromRunning(String runId, RunStatus next, String error) {
        return transition(runId, RunStatus.RUNNING, next, error);
    }

    public TransitionResult pauseFromRunning(String runId, String reason) {
        return transition(runId, RunStatus.RUNNING, RunStatus.PAUSED, reason);
    }

    public TransitionResult interruptIfActive(String runId, String reason) {
        for (int i = 0; i < MAX_CAS_RETRIES; i++) {
            RunStatus current = trajectoryStore.findRunStatus(runId);
            if (isTerminal(current)) {
                return new TransitionResult(current, false);
            }
            if (current == RunStatus.PAUSED) {
                return new TransitionResult(RunStatus.PAUSED, false);
            }
            if (trajectoryStore.transitionRunStatus(runId, current, RunStatus.PAUSED, reason)) {
                return new TransitionResult(RunStatus.PAUSED, true);
            }
        }
        return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
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
