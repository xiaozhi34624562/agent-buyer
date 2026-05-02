package com.ai.agent.application;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.subagent.model.ChildReleaseReason;
import com.ai.agent.subagent.runtime.ChildRunRegistry;
import com.ai.agent.subagent.model.ParentLinkStatus;
import com.ai.agent.tool.runtime.RunEventSinkRegistry;
import com.ai.agent.tool.runtime.ToolResultCloser;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.trajectory.port.TrajectoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运行中断服务
 * <p>
 * 负责处理用户发起的运行中断请求，包括关闭正在执行的工具调用、
 * 中断子运行、更新运行状态等操作，使运行进入暂停状态。
 * </p>
 */
@Service
public final class RunInterruptService {
    private static final Logger log = LoggerFactory.getLogger(RunInterruptService.class);

    private final AgentProperties properties;
    private final RunAccessManager runAccessManager;
    private final RunStateMachine stateMachine;
    private final RedisToolStore redisToolStore;
    private final ToolResultCloser toolResultCloser;
    private final RunEventSinkRegistry sinkRegistry;
    private final ChildRunRegistry childRunRegistry;
    private final TrajectoryStore trajectoryStore;

    public RunInterruptService(
            AgentProperties properties,
            RunAccessManager runAccessManager,
            RunStateMachine stateMachine,
            RedisToolStore redisToolStore,
            ToolResultCloser toolResultCloser,
            RunEventSinkRegistry sinkRegistry,
            ChildRunRegistry childRunRegistry,
            TrajectoryStore trajectoryStore
    ) {
        this.properties = properties;
        this.runAccessManager = runAccessManager;
        this.stateMachine = stateMachine;
        this.redisToolStore = redisToolStore;
        this.toolResultCloser = toolResultCloser;
        this.sinkRegistry = sinkRegistry;
        this.childRunRegistry = childRunRegistry;
        this.trajectoryStore = trajectoryStore;
    }

    /**
     * 中断指定运行
     * <p>
     * 执行中断操作：校验用户权限、检查运行状态、中断工具调用、中断子运行、更新状态。
     * </p>
     *
     * @param userId 用户ID
     * @param runId  运行ID
     * @return 中断响应结果，包含运行状态、是否变更、被中断的子运行数量等
     * @throws InterruptDisabledException 当中断功能被禁用时抛出
     */
    public InterruptRunResponse interrupt(String userId, String runId) {
        if (!properties.getRuntime().isInterruptEnabled()) {
            throw new InterruptDisabledException();
        }
        runAccessManager.assertOwner(runId, userId);
        RunStatus current = trajectoryStore.findRunStatus(runId);
        if (isTerminal(current)) {
            return new InterruptRunResponse(runId, current, false, null, 0);
        }
        List<ToolTerminal> terminals = redisToolStore.interrupt(runId, "user_interrupt");
        toolResultCloser.closeTerminals(runId, terminals, sinkRegistry.find(runId).orElse(null));
        int interruptedChildren = interruptChildren(runId);
        RunStateMachine.TransitionResult transition = stateMachine.interruptIfActive(runId, "user_interrupt");
        if (isTerminal(transition.status())) {
            redisToolStore.clearInterrupt(runId);
        }
        log.info(
                "agent run interrupted runId={} status={} changed={} childCount={}",
                runId,
                transition.status(),
                transition.changed(),
                interruptedChildren
        );
        return new InterruptRunResponse(
                runId,
                transition.status(),
                transition.changed(),
                transition.status() == RunStatus.PAUSED ? "user_input" : null,
                interruptedChildren
        );
    }

    /**
     * 判断运行状态是否为终态
     * <p>
     * 终态包括：成功、失败、失败已恢复、取消、超时。
     * </p>
     *
     * @param status 运行状态
     * @return true表示为终态，false表示非终态
     */
    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.FAILED_RECOVERED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMEOUT;
    }

    /**
     * 中断父运行下的所有活跃子运行
     * <p>
     * 遍历所有活跃子运行，执行工具中断、状态更新、父子链接释放等操作。
     * </p>
     *
     * @param parentRunId 父运行ID
     * @return 被中断的子运行数量
     */
    private int interruptChildren(String parentRunId) {
        int count = 0;
        for (var child : childRunRegistry.findActiveChildren(parentRunId)) {
            redisToolStore.interrupt(child.childRunId(), "parent_interrupted");
            trajectoryStore.updateRunStatus(child.childRunId(), RunStatus.PAUSED, "parent interrupted");
            trajectoryStore.updateParentLinkStatus(child.childRunId(), ParentLinkStatus.DETACHED_BY_INTERRUPT.name());
            childRunRegistry.release(
                    parentRunId,
                    child.childRunId(),
                    ChildReleaseReason.INTERRUPTED,
                    ParentLinkStatus.DETACHED_BY_INTERRUPT
            );
            count++;
        }
        return count;
    }

    /**
     * 中断运行响应结果
     */
    public record InterruptRunResponse(
            String runId,
            RunStatus status,
            boolean changed,
            String nextActionRequired,
            int interruptedChildren
    ) {
    }

    /**
     * 中断功能禁用异常
     * <p>
     * 当系统配置禁用了中断功能时抛出此异常。
     * </p>
     */
    public static final class InterruptDisabledException extends RuntimeException {
    }
}
