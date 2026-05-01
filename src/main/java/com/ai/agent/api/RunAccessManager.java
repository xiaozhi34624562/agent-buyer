package com.ai.agent.api;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class RunAccessManager {
    private final TrajectoryStore trajectoryStore;
    private final ContinuationLockService continuationLockService;
    private final RedisToolStore redisToolStore;
    private final RunStateMachine stateMachine;

    @Autowired
    public RunAccessManager(
            TrajectoryStore trajectoryStore,
            ContinuationLockService continuationLockService,
            RedisToolStore redisToolStore
    ) {
        this.trajectoryStore = trajectoryStore;
        this.continuationLockService = continuationLockService;
        this.redisToolStore = redisToolStore;
        this.stateMachine = new RunStateMachine(trajectoryStore);
    }

    public void assertOwner(String runId, String userId) {
        String owner = trajectoryStore.findRunUserId(runId);
        if (owner == null) {
            throw new RunNotFoundException("run not found: " + runId);
        }
        if (!owner.equals(userId)) {
            throw new RunAccessDeniedException("access denied for run " + runId);
        }
    }

    public void assertCanQuery(String runId, String userId) {
        assertOwner(runId, userId);
    }

    public void assertCanAbort(String runId, String userId) {
        assertOwner(runId, userId);
    }

    public AbortDecision abortIfActive(String runId, String userId) {
        assertOwner(runId, userId);
        RunStateMachine.TransitionResult result = stateMachine.abortIfActive(runId, "user_abort");
        return new AbortDecision(result.status(), result.changed());
    }

    public void assertCanContinue(String runId, String userId) {
        assertOwner(runId, userId);
        assertContinuable(runId);
    }

    public void requireOwner(String userId, String runId) {
        assertOwner(runId, userId);
    }

    public ContinuationPermit acquireContinuation(String userId, String runId) {
        assertOwner(runId, userId);
        ContinuationLockService.Lock lock = continuationLockService.acquire(runId);
        if (lock == null) {
            throw new RunContinuationLockedException("run continuation is already locked: " + runId);
        }
        try {
            RunStatus status = requireContinuable(runId);
            if (status == RunStatus.PAUSED && redisToolStore != null) {
                redisToolStore.clearInterrupt(runId);
            }
            if (!stateMachine.startContinuation(runId, status).changed()) {
                throw new RunContinuationNotAllowedException(
                        "run must still be WAITING_USER_CONFIRMATION or PAUSED before continuation starts: " + runId
                );
            }
            if (status == RunStatus.PAUSED && redisToolStore != null && redisToolStore.interruptRequested(runId)) {
                stateMachine.pauseFromRunning(runId, "user_interrupt");
                throw new RunContinuationNotAllowedException(
                        "run was interrupted before continuation start: " + runId
                );
            }
            return new ContinuationPermit(lock, status);
        } catch (RuntimeException e) {
            continuationLockService.release(lock);
            throw e;
        }
    }

    public void restoreWaitingAfterRejectedSubmit(ContinuationPermit permit) {
        if (permit != null) {
            restoreWaitingStatus(permit);
            continuationLockService.release(permit.lock);
        }
    }

    public void restoreWaitingAfterContinuationStartFailure(ContinuationPermit permit) {
        if (permit != null) {
            restoreWaitingStatus(permit);
        }
    }

    public void releaseContinuation(ContinuationPermit permit) {
        if (permit != null) {
            continuationLockService.release(permit.lock);
        }
    }

    private void restoreWaitingStatus(ContinuationPermit permit) {
        stateMachine.restoreAfterContinuationFailure(permit.runId(), permit.previousStatus);
    }

    private void assertContinuable(String runId) {
        requireContinuable(runId);
    }

    private RunStatus requireContinuable(String runId) {
        RunStatus status = trajectoryStore.findRunStatus(runId);
        if (status != RunStatus.WAITING_USER_CONFIRMATION && status != RunStatus.PAUSED) {
            throw new RunContinuationNotAllowedException(
                    "run must be WAITING_USER_CONFIRMATION or PAUSED before continuation: " + status
            );
        }
        return status;
    }

    public static final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String message) {
            super(message);
        }
    }

    public static final class RunAccessDeniedException extends RuntimeException {
        public RunAccessDeniedException(String message) {
            super(message);
        }
    }

    public static final class RunContinuationNotAllowedException extends RuntimeException {
        public RunContinuationNotAllowedException(String message) {
            super(message);
        }
    }

    public static final class RunContinuationLockedException extends RuntimeException {
        public RunContinuationLockedException(String message) {
            super(message);
        }
    }

    public static final class ContinuationPermit {
        private final ContinuationLockService.Lock lock;
        private final RunStatus previousStatus;

        private ContinuationPermit(ContinuationLockService.Lock lock, RunStatus previousStatus) {
            this.lock = lock;
            this.previousStatus = previousStatus;
        }

        public String runId() {
            return lock.runId();
        }

        public RunStatus previousStatus() {
            return previousStatus;
        }
    }

    public record AbortDecision(RunStatus status, boolean changed) {
    }
}
