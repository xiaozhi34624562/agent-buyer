package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.subagent.ChildReleaseReason;
import com.ai.agent.subagent.ChildRunRegistry;
import com.ai.agent.subagent.ParentLinkStatus;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.FAILED_RECOVERED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMEOUT;
    }

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

    public record InterruptRunResponse(
            String runId,
            RunStatus status,
            boolean changed,
            String nextActionRequired,
            int interruptedChildren
    ) {
    }

    public static final class InterruptDisabledException extends RuntimeException {
    }
}
