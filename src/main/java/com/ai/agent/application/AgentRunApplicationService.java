package com.ai.agent.application;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.loop.AgentLoop;
import com.ai.agent.tool.runtime.ToolResultCloser;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.trajectory.dto.AgentRunTrajectoryDto;
import com.ai.agent.trajectory.query.TrajectoryQueryService;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.dto.ContinueRunRequest;
import com.ai.agent.web.sse.AgentEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Agent运行应用服务
 * <p>
 * Agent运行的核心应用服务，提供创建运行、继续运行、查询运行轨迹、
 * 中止运行、中断运行等操作，是用户与Agent系统交互的主要入口。
 * </p>
 */
@Service
public final class AgentRunApplicationService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunApplicationService.class);

    private final AgentLoop agentLoop;
    private final RunAdmissionController admissionController;
    private final RedisRateLimiter rateLimiter;
    private final AgentRequestPolicy requestPolicy;
    private final RunAccessManager runAccessManager;
    private final ContinuationLockService continuationLockService;
    private final RedisToolStore redisToolStore;
    private final ToolResultCloser toolResultCloser;
    private final TrajectoryQueryService trajectoryQueryService;
    private final RunInterruptService interruptService;

    @Autowired
    public AgentRunApplicationService(
            AgentLoop agentLoop,
            RunAdmissionController admissionController,
            RedisRateLimiter rateLimiter,
            AgentRequestPolicy requestPolicy,
            RunAccessManager runAccessManager,
            ContinuationLockService continuationLockService,
            RedisToolStore redisToolStore,
            ToolResultCloser toolResultCloser,
            TrajectoryQueryService trajectoryQueryService,
            RunInterruptService interruptService
    ) {
        this.agentLoop = agentLoop;
        this.admissionController = admissionController;
        this.rateLimiter = rateLimiter;
        this.requestPolicy = requestPolicy;
        this.runAccessManager = runAccessManager;
        this.continuationLockService = continuationLockService;
        this.redisToolStore = redisToolStore;
        this.toolResultCloser = toolResultCloser;
        this.trajectoryQueryService = trajectoryQueryService;
        this.interruptService = interruptService;
    }

    /**
     * 创建新的Agent运行
     * <p>
     * 校验请求参数、检查服务状态、执行限流判断，然后返回运行计划供执行器调度。
     * </p>
     *
     * @param userId  用户ID
     * @param request Agent运行请求
     * @return 运行流计划，包含执行动作和拒绝回调
     * @throws ServiceUnavailableException 当服务正在关闭时抛出
     * @throws RateLimitExceededException  当用户超过限流阈值时抛出
     */
    public RunStreamPlan createRun(String userId, AgentRunRequest request) {
        requestPolicy.validateCreateRun(request);
        if (!admissionController.isAccepting()) {
            log.warn("agent run rejected because service is shutting down");
            throw new ServiceUnavailableException();
        }
        if (rateLimiter != null && !rateLimiter.allowRun(userId)) {
            log.warn("agent run rejected by rate limit userId={}", userId);
            throw new RateLimitExceededException();
        }
        log.info("agent run accepted userId={} messageCount={}", userId, request.messages().size());
        return new RunStreamPlan(null, sink -> agentLoop.run(userId, request, sink), () -> {
        });
    }

    /**
     * 继续已有的Agent运行
     * <p>
     * 校验请求参数、获取继续运行许可、返回运行计划供执行器调度。
     * </p>
     *
     * @param userId  用户ID
     * @param runId   运行ID
     * @param request 继续运行请求
     * @return 运行流计划，包含执行动作和拒绝回调
     * @throws ServiceUnavailableException 当服务正在关闭时抛出
     */
    public RunStreamPlan continueRun(String userId, String runId, ContinueRunRequest request) {
        requestPolicy.validateContinueRun(request);
        if (!admissionController.isAccepting()) {
            log.warn("agent continuation rejected because service is shutting down runId={} userId={}", runId, userId);
            throw new ServiceUnavailableException();
        }
        RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation(userId, runId);
        log.info("agent continuation accepted runId={} userId={}", runId, userId);
        return new RunStreamPlan(
                runId,
                sink -> agentLoop.continueRun(userId, runId, request.message(), sink, permit),
                () -> runAccessManager.restoreWaitingAfterRejectedSubmit(permit)
        );
    }

    /**
     * 查询运行轨迹
     * <p>
     * 获取指定运行的完整轨迹信息，包括消息历史、工具调用等。
     * </p>
     *
     * @param userId 用户ID
     * @param runId  运行ID
     * @return 运行轨迹数据
     */
    public AgentRunTrajectoryDto queryRun(String userId, String runId) {
        runAccessManager.assertCanQuery(runId, userId);
        log.info("trajectory requested runId={} userId={}", runId, userId);
        return trajectoryQueryService.getTrajectory(runId);
    }

    /**
     * 中止运行
     * <p>
     * 将运行标记为取消状态，关闭所有正在执行的工具调用，释放继续运行锁。
     * </p>
     *
     * @param userId 用户ID
     * @param runId  运行ID
     * @return 中止响应结果
     */
    public AbortRunResponse abortRun(String userId, String runId) {
        log.info("agent abort requested runId={} userId={}", runId, userId);
        RunAccessManager.AbortDecision abort = runAccessManager.abortIfActive(runId, userId);
        if (abort.changed()) {
            toolResultCloser.closeTerminals(runId, redisToolStore.abort(runId, "user_abort"), null);
            continuationLockService.releaseRun(runId);
        }
        return new AbortRunResponse(runId, abort.status(), abort.changed());
    }

    /**
     * 中断运行
     * <p>
     * 将运行暂停，保留当前状态以便后续继续执行。
     * </p>
     *
     * @param userId 用户ID
     * @param runId  运行ID
     * @return 中断响应结果
     */
    public RunInterruptService.InterruptRunResponse interruptRun(String userId, String runId) {
        log.info("agent interrupt requested runId={} userId={}", runId, userId);
        return interruptService.interrupt(userId, runId);
    }

    /**
     * 运行流执行动作接口
     * <p>
     * 定义执行Agent运行并返回结果的方法签名。
     * </p>
     */
    @FunctionalInterface
    public interface RunStreamAction {
        AgentRunResult run(AgentEventSink sink);
    }

    /**
     * 运行流计划
     * <p>
     * 包含运行ID、执行动作和执行器拒绝时的回调处理。
     * </p>
     */
    public record RunStreamPlan(
            String runId,
            RunStreamAction action,
            Runnable onExecutorRejected
    ) {
        public RunStreamPlan {
            if (onExecutorRejected == null) {
                onExecutorRejected = () -> {
                };
            }
        }
    }

    /**
     * 中止运行响应结果
     */
    public record AbortRunResponse(String runId, RunStatus status, boolean changed) {
    }

    /**
     * 限流超限异常
     * <p>
     * 当用户调用频率超过系统配置的限流阈值时抛出此异常。
     * </p>
     */
    public static final class RateLimitExceededException extends RuntimeException {
    }

    /**
     * 服务不可用异常
     * <p>
     * 当服务正在关闭或不可用时抛出此异常。
     * </p>
     */
    public static final class ServiceUnavailableException extends RuntimeException {
    }
}
