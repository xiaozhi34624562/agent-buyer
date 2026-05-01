package com.ai.agent.subagent;

import com.ai.agent.api.AgentEventSink;
import com.ai.agent.api.AgentRunResult;
import com.ai.agent.api.AgentTurnOrchestrator;
import com.ai.agent.api.ErrorEvent;
import com.ai.agent.api.FinalEvent;
import com.ai.agent.api.TextDeltaEvent;
import com.ai.agent.api.ToolProgressEvent;
import com.ai.agent.api.ToolResultEvent;
import com.ai.agent.api.ToolUseEvent;
import com.ai.agent.api.ToolCallCoordinator;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import com.ai.agent.trajectory.ChildRunCreation;
import com.ai.agent.tool.CancellationToken;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.util.Ids;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Lazy
@Service
public final class DefaultSubAgentRunner implements SubAgentRunner {
    private final AgentProperties properties;
    private final SubAgentRegistry subAgentRegistry;
    private final ChildRunRegistry childRunRegistry;
    private final TrajectoryStore trajectoryStore;
    private final TrajectoryReader trajectoryReader;
    private final RunContextStore runContextStore;
    private final AgentTurnOrchestrator turnOrchestrator;
    private final ToolCallCoordinator toolCallCoordinator;
    private final RedisToolStore redisToolStore;
    private final ExecutorService subAgentExecutor;

    public DefaultSubAgentRunner(
            AgentProperties properties,
            SubAgentRegistry subAgentRegistry,
            ChildRunRegistry childRunRegistry,
            TrajectoryStore trajectoryStore,
            TrajectoryReader trajectoryReader,
            RunContextStore runContextStore,
            AgentTurnOrchestrator turnOrchestrator,
            ToolCallCoordinator toolCallCoordinator,
            RedisToolStore redisToolStore,
            @Qualifier("subAgentExecutor") ExecutorService subAgentExecutor
    ) {
        this.properties = properties;
        this.subAgentRegistry = subAgentRegistry;
        this.childRunRegistry = childRunRegistry;
        this.trajectoryStore = trajectoryStore;
        this.trajectoryReader = trajectoryReader;
        this.runContextStore = runContextStore;
        this.turnOrchestrator = turnOrchestrator;
        this.toolCallCoordinator = toolCallCoordinator;
        this.redisToolStore = redisToolStore;
        this.subAgentExecutor = subAgentExecutor;
    }

    @Override
    public SubAgentResult run(SubAgentTask task, CancellationToken token) throws Exception {
        if (token.isCancellationRequested()) {
            return new SubAgentResult(null, RunStatus.CANCELLED, "SubAgent was cancelled before start.", true);
        }
        RunContext parentContext = runContextStore.load(task.parentRunId());
        SubAgentProfile profile = subAgentRegistry.resolve(task.agentType());
        List<String> allowedTools = profile.allowedToolNames(parentContext.effectiveAllowedTools());
        String childRunId = Ids.newId("run");
        int parentUserTurnNo = parentUserTurnNo(task.parentRunId());
        ReserveChildResult reserve = childRunRegistry.reserve(new ReserveChildCommand(
                task.parentRunId(),
                childRunId,
                task.parentToolCallId(),
                profile.agentType(),
                parentUserTurnNo,
                Instant.now()
        ));
        if (!reserve.accepted()) {
            throw new SubAgentBudgetExceededException(reserve);
        }
        childRunId = reserve.childRunId();
        boolean childRowCreated = false;
        boolean childRunStarted = false;
        Future<AgentRunResult> future = null;
        try {
            String reservedChildRunId = childRunId;
            ChildRunCreation creation = trajectoryStore.createChildRun(
                    childRunId,
                    task.userId(),
                    task.parentRunId(),
                    task.parentToolCallId(),
                    profile.agentType(),
                    ParentLinkStatus.LIVE.name()
            );
            childRunId = creation.childRunId();
            if (!creation.created()) {
                if (!reserve.reused()) {
                    childRunRegistry.release(
                            task.parentRunId(),
                            reservedChildRunId,
                            ChildReleaseReason.FAILED,
                            ParentLinkStatus.DETACHED_BY_PARENT_FAILED
                    );
                }
                return recoverExistingChildResult(childRunId);
            }
            childRowCreated = true;
            runContextStore.create(childContext(childRunId, parentContext, allowedTools));
            trajectoryStore.appendMessage(childRunId, LlmMessage.system(Ids.newId("msg"), profile.renderSystemPrompt(task)));
            trajectoryStore.appendMessage(childRunId, LlmMessage.user(Ids.newId("msg"), "Delegated task:\n" + task.task()));
            trajectoryStore.transitionRunStatus(childRunId, RunStatus.CREATED, RunStatus.RUNNING, null);
            childRunStarted = true;
            String finalChildRunId = childRunId;
            future = subAgentExecutor.submit(() -> turnOrchestrator.runSubAgentUntilStop(
                    finalChildRunId,
                    task.parentRunId(),
                    task.userId(),
                    runContextStore.load(finalChildRunId),
                    null,
                    NoopAgentEventSink.INSTANCE
            ));
            AgentRunResult result = awaitChildResult(future, token);
            childRunRegistry.release(
                    task.parentRunId(),
                    childRunId,
                    releaseReason(result.status()),
                    ParentLinkStatus.LIVE
            );
            return new SubAgentResult(
                    childRunId,
                    result.status(),
                    result.finalText(),
                    result.status() != RunStatus.SUCCEEDED
            );
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            if (childRunStarted) {
                toolCallCoordinator.abortRunTools(childRunId, "subagent_wait_timeout", NoopAgentEventSink.INSTANCE);
                trajectoryStore.updateRunStatus(childRunId, RunStatus.TIMEOUT, "subagent wait timeout");
                trajectoryStore.updateParentLinkStatus(childRunId, ParentLinkStatus.DETACHED_BY_TIMEOUT.name());
            }
            childRunRegistry.release(
                    task.parentRunId(),
                    childRunId,
                    ChildReleaseReason.TIMEOUT,
                    ParentLinkStatus.DETACHED_BY_TIMEOUT
            );
            return new SubAgentResult(
                    childRunId,
                    RunStatus.TIMEOUT,
                    "SubAgent did not finish within the wait timeout.",
                    true
            );
        } catch (ParentRunAbortedException e) {
            if (future != null) {
                future.cancel(true);
            }
            boolean interrupted = redisToolStore != null && redisToolStore.interruptRequested(task.parentRunId());
            RunStatus childStatus = interrupted ? RunStatus.PAUSED : RunStatus.CANCELLED;
            String reason = interrupted ? "parent interrupted" : "parent run aborted";
            ParentLinkStatus linkStatus = interrupted
                    ? ParentLinkStatus.DETACHED_BY_INTERRUPT
                    : ParentLinkStatus.DETACHED_BY_PARENT_FAILED;
            ChildReleaseReason releaseReason = interrupted ? ChildReleaseReason.INTERRUPTED : ChildReleaseReason.PARENT_FAILED;
            if (childRunStarted) {
                if (interrupted) {
                    toolCallCoordinator.interruptRunTools(childRunId, "parent_interrupted", NoopAgentEventSink.INSTANCE);
                } else {
                    toolCallCoordinator.abortRunTools(childRunId, "parent_run_aborted", NoopAgentEventSink.INSTANCE);
                }
                trajectoryStore.updateRunStatus(childRunId, childStatus, reason);
                trajectoryStore.updateParentLinkStatus(childRunId, linkStatus.name());
            }
            childRunRegistry.release(
                    task.parentRunId(),
                    childRunId,
                    releaseReason,
                    linkStatus
            );
            return new SubAgentResult(
                    childRunId,
                    childStatus,
                    interrupted
                            ? "SubAgent was interrupted with the parent run."
                            : "SubAgent was cancelled because the parent run was aborted.",
                    true
            );
        } catch (RejectedExecutionException e) {
            if (childRunStarted) {
                failStartedChild(childRunId, "subagent executor rejected");
            }
            childRunRegistry.release(
                    task.parentRunId(),
                    childRunId,
                    ChildReleaseReason.FAILED,
                    ParentLinkStatus.DETACHED_BY_PARENT_FAILED
            );
            return new SubAgentResult(
                    childRunId,
                    RunStatus.FAILED,
                    "SubAgent executor rejected the child run.",
                    true
            );
        } catch (ExecutionException e) {
            if (childRowCreated) {
                failStartedChild(childRunId, childRunStarted ? "subagent execution failed" : "subagent initialization failed");
            }
            childRunRegistry.release(
                    task.parentRunId(),
                    childRunId,
                    ChildReleaseReason.FAILED,
                    ParentLinkStatus.DETACHED_BY_PARENT_FAILED
            );
            return failedResult(childRunId, "SubAgent execution failed: " + failureMessage(e));
        } catch (RuntimeException e) {
            if (childRowCreated) {
                failStartedChild(childRunId, childRunStarted ? "subagent execution failed" : "subagent initialization failed");
            }
            childRunRegistry.release(
                    task.parentRunId(),
                    childRunId,
                    ChildReleaseReason.FAILED,
                    ParentLinkStatus.DETACHED_BY_PARENT_FAILED
            );
            throw e;
        }
    }

    private RunContext childContext(String childRunId, RunContext parentContext, List<String> allowedTools) {
        return new RunContext(
                childRunId,
                allowedTools,
                parentContext.model(),
                parentContext.primaryProvider(),
                parentContext.fallbackProvider(),
                parentContext.providerOptions(),
                parentContext.maxTurns(),
                null,
                null
        );
    }

    private int parentUserTurnNo(String parentRunId) {
        try {
            long userMessageCount = trajectoryReader.loadMessages(parentRunId).stream()
                    .filter(message -> message.role() == MessageRole.USER)
                    .count();
            if (userMessageCount > 0L) {
                return Math.toIntExact(userMessageCount);
            }
        } catch (RuntimeException e) {
            return Math.max(1, trajectoryStore.currentTurn(parentRunId));
        }
        return Math.max(1, trajectoryStore.currentTurn(parentRunId));
    }

    private AgentRunResult awaitChildResult(
            Future<AgentRunResult> future,
            CancellationToken token
    ) throws TimeoutException, ExecutionException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getSubAgent().getWaitTimeoutMs());
        while (true) {
            if (token.isCancellationRequested()) {
                throw new ParentRunAbortedException();
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException("subagent wait timeout");
            }
            long waitMillis = Math.max(1L, Math.min(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Short polling lets parent abort interrupt a long child wait without waiting for the full timeout.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ParentRunAbortedException();
            }
        }
    }

    private SubAgentResult recoverExistingChildResult(String childRunId) {
        RunStatus status;
        try {
            status = trajectoryStore.findRunStatus(childRunId);
        } catch (RuntimeException e) {
            return new SubAgentResult(
                    childRunId,
                    RunStatus.FAILED,
                    "Existing SubAgent child run is not readable.",
                    true
            );
        }
        return new SubAgentResult(
                childRunId,
                status,
                latestAssistantSummary(childRunId, status),
                status != RunStatus.SUCCEEDED
        );
    }

    private String latestAssistantSummary(String childRunId, RunStatus status) {
        try {
            List<LlmMessage> messages = trajectoryReader.loadMessages(childRunId);
            for (int index = messages.size() - 1; index >= 0; index--) {
                LlmMessage message = messages.get(index);
                if (message.role() == MessageRole.ASSISTANT && message.content() != null && !message.content().isBlank()) {
                    return message.content();
                }
            }
        } catch (RuntimeException e) {
            return "Existing SubAgent child run status: " + status.name();
        }
        return "Existing SubAgent child run status: " + status.name();
    }

    private void failStartedChild(String childRunId, String error) {
        trajectoryStore.updateRunStatus(childRunId, RunStatus.FAILED, error);
        trajectoryStore.updateParentLinkStatus(childRunId, ParentLinkStatus.DETACHED_BY_PARENT_FAILED.name());
    }

    private ChildReleaseReason releaseReason(RunStatus status) {
        return status == RunStatus.SUCCEEDED ? ChildReleaseReason.SUCCEEDED : ChildReleaseReason.FAILED;
    }

    private SubAgentResult failedResult(String childRunId, String summary) {
        return new SubAgentResult(
                childRunId,
                RunStatus.FAILED,
                summary,
                true
        );
    }

    private String failureMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return "unknown child execution failure";
        }
        return cause.getMessage();
    }

    private enum NoopAgentEventSink implements AgentEventSink {
        INSTANCE;

        @Override
        public void onTextDelta(TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
        }

        @Override
        public void onFinal(FinalEvent event) {
        }

        @Override
        public void onError(ErrorEvent event) {
        }
    }

    private static final class ParentRunAbortedException extends RuntimeException {
    }
}
