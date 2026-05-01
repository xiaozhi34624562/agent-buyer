package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.tool.Tool;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public final class AgentTurnOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentTurnOrchestrator.class);

    private final AgentProperties properties;
    private final TranscriptPairValidator transcriptPairValidator;
    private final LlmAttemptService llmAttemptService;
    private final ToolCallCoordinator toolCallCoordinator;
    private final TrajectoryStore trajectoryStore;
    private final TrajectoryReader trajectoryReader;
    private final RunStateMachine stateMachine;

    public AgentTurnOrchestrator(
            AgentProperties properties,
            TranscriptPairValidator transcriptPairValidator,
            LlmAttemptService llmAttemptService,
            ToolCallCoordinator toolCallCoordinator,
            TrajectoryStore trajectoryStore,
            TrajectoryReader trajectoryReader,
            RunStateMachine stateMachine
    ) {
        this.properties = properties;
        this.transcriptPairValidator = transcriptPairValidator;
        this.llmAttemptService = llmAttemptService;
        this.toolCallCoordinator = toolCallCoordinator;
        this.trajectoryStore = trajectoryStore;
        this.trajectoryReader = trajectoryReader;
        this.stateMachine = stateMachine;
    }

    public AgentRunResult runUntilStop(
            String runId,
            String userId,
            RunContext runContext,
            LlmParams params,
            AgentEventSink sink
    ) {
        Instant deadline = Instant.now().plusMillis(properties.getAgentLoop().getRunWallclockTimeoutMs());
        int maxTurns = runContext.maxTurns();
        List<Tool> allowedTools = toolCallCoordinator.toolsFromContext(runContext.effectiveAllowedTools());
        String model = runContext.model();

        for (int i = 0; i < maxTurns; i++) {
            AgentRunResult stopped = stopIfNotRunning(runId);
            if (stopped != null) {
                return stopped;
            }
            if (Instant.now().isAfter(deadline)) {
                toolCallCoordinator.abortRunTools(runId, "run_wallclock_timeout", sink);
                AgentRunResult terminal = transitionFromRunning(runId, RunStatus.TIMEOUT, "run wallclock timeout", null);
                if (terminal.status() != RunStatus.TIMEOUT) {
                    return terminal;
                }
                sink.onError(new ErrorEvent(runId, "run timeout"));
                log.warn("agent run timed out by wallclock maxTurns={} timeoutMs={}", maxTurns, properties.getAgentLoop().getRunWallclockTimeoutMs());
                return terminal;
            }

            int turnNo = trajectoryStore.nextTurn(runId);
            List<LlmMessage> messages = trajectoryReader.loadMessages(runId);
            transcriptPairValidator.validate(messages);
            String attemptId = Ids.newId("att");
            try (MDC.MDCCloseable ignoredAttempt = MDC.putCloseable("attemptId", attemptId)) {
                log.info("llm attempt started turnNo={} messageCount={} model={}", turnNo, messages.size(), model);
                LlmStreamResult result;
                try {
                    result = llmAttemptService.executeAttempt(
                            runId,
                            turnNo,
                            attemptId,
                            runContext.primaryProvider(),
                            model,
                            params,
                            messages,
                            allowedTools,
                            sink
                    );
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    log.info("llm attempt completed finishReason={} toolCallCount={}", result.finishReason(), result.toolCalls().size());
                } catch (Exception e) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    AgentRunResult terminal = transitionFromRunning(runId, RunStatus.FAILED, e.getMessage(), null);
                    if (terminal.status() != RunStatus.FAILED) {
                        return terminal;
                    }
                    sink.onError(new ErrorEvent(runId, e.getMessage()));
                    log.error("llm attempt failed", e);
                    return terminal;
                }

                if (result.toolCalls().isEmpty()) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    trajectoryStore.appendMessage(runId, LlmMessage.assistant(Ids.newId("msg"), result.content(), List.of()));
                    AgentRunResult terminal = transitionFromRunning(runId, RunStatus.SUCCEEDED, null, result.content());
                    if (terminal.status() != RunStatus.SUCCEEDED) {
                        return terminal;
                    }
                    sink.onFinal(new FinalEvent(runId, result.content(), RunStatus.SUCCEEDED, null));
                    log.info("agent run completed with final text");
                    return terminal;
                }

                stopped = stopIfNotRunning(runId);
                if (stopped != null) {
                    return stopped;
                }
                ToolCallCoordinator.ToolStepResult toolStep;
                try {
                    toolStep = toolCallCoordinator.processToolCalls(runId, userId, result, allowedTools, sink);
                } catch (ToolCallCoordinator.ToolResultTimeoutException e) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    AgentRunResult terminal = transitionFromRunning(runId, RunStatus.TIMEOUT, "tool result timeout", null);
                    if (terminal.status() != RunStatus.TIMEOUT) {
                        return terminal;
                    }
                    sink.onError(new ErrorEvent(runId, "tool result timeout"));
                    log.warn("tool result wait timed out", e);
                    return terminal;
                }
                stopped = stopIfNotRunning(runId);
                if (stopped != null) {
                    return stopped;
                }
                if (toolStep.pendingConfirm() != null) {
                    AgentRunResult terminal = transitionFromRunning(
                            runId,
                            RunStatus.WAITING_USER_CONFIRMATION,
                            null,
                            toolStep.pendingConfirm().summary()
                    );
                    if (terminal.status() != RunStatus.WAITING_USER_CONFIRMATION) {
                        return terminal;
                    }
                    sink.onFinal(new FinalEvent(
                            runId,
                            toolStep.pendingConfirm().summary(),
                            RunStatus.WAITING_USER_CONFIRMATION,
                            "user_confirmation"
                    ));
                    log.info("agent run waiting for user confirmation");
                    return terminal;
                }
            }
        }

        toolCallCoordinator.abortRunTools(runId, "max_turns_exceeded", sink);
        AgentRunResult terminal = transitionFromRunning(runId, RunStatus.FAILED, "max turns exceeded", null);
        if (terminal.status() != RunStatus.FAILED) {
            return terminal;
        }
        sink.onError(new ErrorEvent(runId, "max turns exceeded"));
        log.warn("agent run failed because max turns exceeded maxTurns={}", maxTurns);
        return terminal;
    }

    private AgentRunResult stopIfNotRunning(String runId) {
        RunStatus status = trajectoryStore.findRunStatus(runId);
        if (status == RunStatus.RUNNING) {
            return null;
        }
        log.warn("agent loop stopped because run is no longer RUNNING status={}", status);
        return new AgentRunResult(runId, status, null);
    }

    private AgentRunResult transitionFromRunning(String runId, RunStatus status, String error, String finalText) {
        RunStateMachine.TransitionResult result = stateMachine.completeFromRunning(runId, status, error);
        if (result.changed()) {
            return new AgentRunResult(runId, status, finalText);
        }
        RunStatus current = result.status();
        log.warn("agent terminal transition lost race targetStatus={} currentStatus={}", status, current);
        return new AgentRunResult(runId, current, null);
    }
}
