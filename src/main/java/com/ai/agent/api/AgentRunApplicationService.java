package com.ai.agent.api;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryQueryService;
import com.ai.agent.trajectory.dto.AgentRunTrajectoryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public AgentRunTrajectoryDto queryRun(String userId, String runId) {
        runAccessManager.assertCanQuery(runId, userId);
        log.info("trajectory requested runId={} userId={}", runId, userId);
        return trajectoryQueryService.getTrajectory(runId);
    }

    public AbortRunResponse abortRun(String userId, String runId) {
        log.info("agent abort requested runId={} userId={}", runId, userId);
        RunAccessManager.AbortDecision abort = runAccessManager.abortIfActive(runId, userId);
        if (abort.changed()) {
            toolResultCloser.closeTerminals(runId, redisToolStore.abort(runId, "user_abort"), null);
            continuationLockService.releaseRun(runId);
        }
        return new AbortRunResponse(runId, abort.status(), abort.changed());
    }

    public RunInterruptService.InterruptRunResponse interruptRun(String userId, String runId) {
        log.info("agent interrupt requested runId={} userId={}", runId, userId);
        return interruptService.interrupt(userId, runId);
    }

    @FunctionalInterface
    public interface RunStreamAction {
        AgentRunResult run(AgentEventSink sink);
    }

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

    public record AbortRunResponse(String runId, RunStatus status, boolean changed) {
    }

    public static final class RateLimitExceededException extends RuntimeException {
    }

    public static final class ServiceUnavailableException extends RuntimeException {
    }
}
