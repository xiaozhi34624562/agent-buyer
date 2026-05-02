package com.ai.agent.application;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.trajectory.port.TrajectoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 运行访问权限管理器。
 * <p>
 * 负责 Agent 运行的访问控制和权限验证，确保：
 * - 用户只能访问自己创建的运行
 * - 运行状态符合操作要求（如继续运行需要处于 PAUSED 或 WAITING_USER_CONFIRMATION）
 * - 继续操作时正确管理锁和状态转换
 * </p>
 */
@Component
public final class RunAccessManager {
    private final TrajectoryStore trajectoryStore;
    private final ContinuationLockService continuationLockService;
    private final RedisToolStore redisToolStore;
    private final RunStateMachine stateMachine;

    /**
     * 构造访问管理器。
     *
     * @param trajectoryStore        轨迹存储
     * @param continuationLockService 继续锁服务
     * @param redisToolStore         Redis 工具存储
     */
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

    /**
     * 断言用户是运行的所有者。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     * @throws RunNotFoundException  运行不存在
     * @throws RunAccessDeniedException 用户无权访问
     */
    public void assertOwner(String runId, String userId) {
        String owner = trajectoryStore.findRunUserId(runId);
        if (owner == null) {
            throw new RunNotFoundException("run not found: " + runId);
        }
        if (!owner.equals(userId)) {
            throw new RunAccessDeniedException("access denied for run " + runId);
        }
    }

    /**
     * 断言用户可以查询运行。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     */
    public void assertCanQuery(String runId, String userId) {
        assertOwner(runId, userId);
    }

    /**
     * 断言用户可以终止运行。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     */
    public void assertCanAbort(String runId, String userId) {
        assertOwner(runId, userId);
    }

    /**
     * 如果运行处于活跃状态则终止。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     * @return 终止决策，包含最终状态和是否发生变更
     */
    public AbortDecision abortIfActive(String runId, String userId) {
        assertOwner(runId, userId);
        RunStateMachine.TransitionResult result = stateMachine.abortIfActive(runId, "user_abort");
        return new AbortDecision(result.status(), result.changed());
    }

    /**
     * 断言运行可以继续。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     */
    public void assertCanContinue(String runId, String userId) {
        assertOwner(runId, userId);
        assertContinuable(runId);
    }

    /**
     * 要求用户是运行所有者。
     *
     * @param userId 用户标识
     * @param runId  运行标识
     */
    public void requireOwner(String userId, String runId) {
        assertOwner(runId, userId);
    }

    /**
     * 获取继续运行的许可。
     * <p>
     * 检查运行状态、获取锁、清除中断信号，并准备继续执行。
     * 如果运行处于 PAUSED 状态且有中断请求，会恢复到 PAUSED 状态。
     * </p>
     *
     * @param userId 用户标识
     * @param runId  运行标识
     * @return 继续许可，包含锁和之前状态
     * @throws RunContinuationLockedException    继续操作已被锁定
     * @throws RunContinuationNotAllowedException 运行状态不允许继续
     */
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

    /**
     * 提交被拒绝后恢复等待状态。
     *
     * @param permit 继续许可
     */
    public void restoreWaitingAfterRejectedSubmit(ContinuationPermit permit) {
        if (permit != null) {
            restoreWaitingStatus(permit);
            continuationLockService.release(permit.lock);
        }
    }

    /**
     * 继续启动失败后恢复等待状态。
     *
     * @param permit 继续许可
     */
    public void restoreWaitingAfterContinuationStartFailure(ContinuationPermit permit) {
        if (permit != null) {
            restoreWaitingStatus(permit);
        }
    }

    /**
     * 释放继续许可。
     *
     * @param permit 继续许可
     */
    public void releaseContinuation(ContinuationPermit permit) {
        if (permit != null) {
            continuationLockService.release(permit.lock);
        }
    }

    /**
     * 恢复等待状态。
     *
     * @param permit 继续许可
     */
    private void restoreWaitingStatus(ContinuationPermit permit) {
        stateMachine.restoreAfterContinuationFailure(permit.runId(), permit.previousStatus);
    }

    /**
     * 断言运行可以继续。
     *
     * @param runId 运行标识
     */
    private void assertContinuable(String runId) {
        requireContinuable(runId);
    }

    /**
     * 要求运行处于可继续状态。
     *
     * @param runId 运行标识
     * @return 当前运行状态
     * @throws RunContinuationNotAllowedException 运行状态不允许继续
     */
    private RunStatus requireContinuable(String runId) {
        RunStatus status = trajectoryStore.findRunStatus(runId);
        if (status != RunStatus.WAITING_USER_CONFIRMATION && status != RunStatus.PAUSED) {
            throw new RunContinuationNotAllowedException(
                    "run must be WAITING_USER_CONFIRMATION or PAUSED before continuation: " + status
            );
        }
        return status;
    }

    /**
     * 运行不存在异常。
     */
    public static final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 运行访问被拒异常。
     */
    public static final class RunAccessDeniedException extends RuntimeException {
        public RunAccessDeniedException(String message) {
            super(message);
        }
    }

    /**
     * 运行继续不允许异常。
     */
    public static final class RunContinuationNotAllowedException extends RuntimeException {
        public RunContinuationNotAllowedException(String message) {
            super(message);
        }
    }

    /**
     * 运行继续已锁定异常。
     */
    public static final class RunContinuationLockedException extends RuntimeException {
        public RunContinuationLockedException(String message) {
            super(message);
        }
    }

    /**
     * 继续许可，包含锁和之前状态。
     */
    public static final class ContinuationPermit {
        private final ContinuationLockService.Lock lock;
        private final RunStatus previousStatus;

        /**
         * 构造继续许可。
         *
         * @param lock          继续锁
         * @param previousStatus 之前状态
         */
        private ContinuationPermit(ContinuationLockService.Lock lock, RunStatus previousStatus) {
            this.lock = lock;
            this.previousStatus = previousStatus;
        }

        /**
         * 获取运行标识。
         *
         * @return 运行标识
         */
        public String runId() {
            return lock.runId();
        }

        /**
         * 获取之前状态。
         *
         * @return 运行状态
         */
        public RunStatus previousStatus() {
            return previousStatus;
        }
    }

    /**
     * 终止决策，包含最终状态和是否发生变更。
     */
    public record AbortDecision(RunStatus status, boolean changed) {
    }
}
