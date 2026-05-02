package com.ai.agent.application;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.trajectory.port.TrajectoryStore;
import org.springframework.stereotype.Component;

/**
 * 运行状态机。
 * 管理运行生命周期中的状态转换，确保状态变更的原子性和一致性。
 */
@Component
public final class RunStateMachine {
    private static final int MAX_CAS_RETRIES = 5;

    private final TrajectoryStore trajectoryStore;

    public RunStateMachine(TrajectoryStore trajectoryStore) {
        this.trajectoryStore = trajectoryStore;
    }

    /**
     * 启动运行，将状态从CREATED转换为RUNNING。
     *
     * @param runId 运行ID
     * @return 状态转换结果
     */
    public TransitionResult startRun(String runId) {
        return transition(runId, RunStatus.CREATED, RunStatus.RUNNING, null);
    }

    /**
     * 启动续运行，尝试从当前状态转换到RUNNING。
     *
     * @param runId 运行ID
     * @return 状态转换结果
     */
    public TransitionResult startContinuation(String runId) {
        RunStatus current = trajectoryStore.findRunStatus(runId);
        return startContinuation(runId, current);
    }

    /**
     * 启动续运行，从指定当前状态转换到RUNNING。
     *
     * @param runId   运行ID
     * @param current 当前状态
     * @return 状态转换结果
     */
    public TransitionResult startContinuation(String runId, RunStatus current) {
        if (current == RunStatus.WAITING_USER_CONFIRMATION || current == RunStatus.PAUSED) {
            return transition(runId, current, RunStatus.RUNNING, null);
        }
        return new TransitionResult(current, false);
    }

    /**
     * 续运行失败后恢复到等待用户确认状态。
     *
     * @param runId 运行ID
     * @return 状态转换结果
     */
    public TransitionResult restoreWaitingAfterContinuationFailure(String runId) {
        return restoreAfterContinuationFailure(runId, RunStatus.WAITING_USER_CONFIRMATION);
    }

    /**
     * 续运行失败后恢复到指定状态。
     *
     * @param runId         运行ID
     * @param previousStatus 要恢复到的状态
     * @return 状态转换结果
     */
    public TransitionResult restoreAfterContinuationFailure(String runId, RunStatus previousStatus) {
        if (previousStatus != RunStatus.WAITING_USER_CONFIRMATION && previousStatus != RunStatus.PAUSED) {
            return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
        }
        if (trajectoryStore.transitionRunStatus(runId, RunStatus.RUNNING, previousStatus, null)) {
            return new TransitionResult(previousStatus, true);
        }
        return new TransitionResult(trajectoryStore.findRunStatus(runId), false);
    }

    /**
     * 从RUNNING状态完成运行，转换到指定状态。
     *
     * @param runId 运行ID
     * @param next  目标状态
     * @param error 错误信息
     * @return 状态转换结果
     */
    public TransitionResult completeFromRunning(String runId, RunStatus next, String error) {
        return transition(runId, RunStatus.RUNNING, next, error);
    }

    /**
     * 从RUNNING状态暂停运行。
     *
     * @param runId  运行ID
     * @param reason 暂停原因
     * @return 状态转换结果
     */
    public TransitionResult pauseFromRunning(String runId, String reason) {
        return transition(runId, RunStatus.RUNNING, RunStatus.PAUSED, reason);
    }

    /**
     * 如果运行处于活动状态则中断。
     *
     * @param runId  运行ID
     * @param reason 中断原因
     * @return 状态转换结果
     */
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

    /**
     * 确认超时时转换状态。
     *
     * @param runId 运行ID
     * @return 状态转换结果
     */
    public TransitionResult confirmationTimeout(String runId) {
        return transition(
                runId,
                RunStatus.WAITING_USER_CONFIRMATION,
                RunStatus.TIMEOUT,
                "confirmation timeout"
        );
    }

    /**
     * 如果运行处于活动状态则中止。
     *
     * @param runId  运行ID
     * @param reason 中止原因
     * @return 状态转换结果
     */
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

    /**
     * 标记初始化失败。
     *
     * @param runId 运行ID
     * @param error 错误信息
     */
    public void markInitializationFailed(String runId, String error) {
        trajectoryStore.updateRunStatus(runId, RunStatus.FAILED, error);
    }

    /**
     * 修复终端状态。
     *
     * @param runId 运行ID
     * @param status 状态
     * @param error  错误信息
     */
    public void repairTerminal(String runId, RunStatus status, String error) {
        trajectoryStore.updateRunStatus(runId, status, error);
    }

    /**
     * 检查状态是否为终端状态。
     *
     * @param status 运行状态
     * @return 如果是终端状态则返回true
     */
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

    /**
     * 状态转换结果。
     *
     * @param status  转换后的状态
     * @param changed 是否发生状态变更
     */
    public record TransitionResult(RunStatus status, boolean changed) {
    }
}
