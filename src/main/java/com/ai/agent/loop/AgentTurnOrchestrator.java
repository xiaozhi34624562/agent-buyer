package com.ai.agent.loop;

import com.ai.agent.application.RunStateMachine;
import com.ai.agent.budget.AgentExecutionBudget;
import com.ai.agent.budget.LlmCallBudgetExceededException;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.context.ContextViewBuilder;
import com.ai.agent.llm.context.ProviderContextView;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.skill.command.SkillCommandException;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.dto.LlmParams;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.FinalEvent;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Agent轮次编排器，负责多轮对话的执行控制。
 * <p>
 * 主要职责包括：
 * <ul>
 *     <li>执行多轮LLM调用直到终止条件满足</li>
 *     <li>处理运行超时和最大轮次限制</li>
 *     <li>处理LLM预算超限暂停</li>
 *     <li>处理工具调用的待确认和待输入状态</li>
 * </ul>
 * </p>
 */
@Service
public final class AgentTurnOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentTurnOrchestrator.class);

    private final AgentProperties properties;
    private final ContextViewBuilder contextViewBuilder;
    private final LlmAttemptService llmAttemptService;
    private final ToolCallCoordinator toolCallCoordinator;
    private final TrajectoryReader trajectoryReader;
    private final TrajectoryStore trajectoryStore;
    private final RunStateMachine stateMachine;
    private final AgentExecutionBudget executionBudget;

    /**
     * 构造函数，注入所有必要的依赖组件。
     *
     * @param properties         Agent配置属性
     * @param contextViewBuilder 上下文视图构建器
     * @param llmAttemptService  LLM调用尝试服务
     * @param toolCallCoordinator 工具调用协调器
     * @param trajectoryReader   运行轨迹读取器
     * @param trajectoryStore    运行轨迹存储
     * @param stateMachine       运行状态机
     * @param executionBudget    执行预算管理器
     */
    public AgentTurnOrchestrator(
            AgentProperties properties,
            ContextViewBuilder contextViewBuilder,
            LlmAttemptService llmAttemptService,
            ToolCallCoordinator toolCallCoordinator,
            TrajectoryReader trajectoryReader,
            TrajectoryStore trajectoryStore,
            RunStateMachine stateMachine,
            AgentExecutionBudget executionBudget
    ) {
        this.properties = properties;
        this.contextViewBuilder = contextViewBuilder;
        this.llmAttemptService = llmAttemptService;
        this.toolCallCoordinator = toolCallCoordinator;
        this.trajectoryReader = trajectoryReader;
        this.trajectoryStore = trajectoryStore;
        this.stateMachine = stateMachine;
        this.executionBudget = executionBudget;
    }

    /**
     * 执行Agent运行直到终止条件满足。
     *
     * @param runId       运行标识
     * @param userId      用户标识
     * @param runContext  运行上下文
     * @param params      LLM参数配置
     * @param sink        SSE事件接收器
     * @return 运行结果
     */
    public AgentRunResult runUntilStop(
            String runId,
            String userId,
            RunContext runContext,
            LlmParams params,
            AgentEventSink sink
    ) {
        return runUntilStop(runId, runId, userId, runContext, params, sink, false);
    }

    /**
     * 执行子Agent运行直到终止条件满足（简化版本）。
     *
     * @param runId       运行标识
     * @param userId      用户标识
     * @param runContext  运行上下文
     * @param params      LLM参数配置
     * @param sink        SSE事件接收器
     * @return 运行结果
     */
    public AgentRunResult runSubAgentUntilStop(
            String runId,
            String userId,
            RunContext runContext,
            LlmParams params,
            AgentEventSink sink
    ) {
        return runSubAgentUntilStop(runId, runId, userId, runContext, params, sink);
    }

    /**
     * 执行子Agent运行直到终止条件满足（带独立预算范围）。
     *
     * @param runId              运行标识
     * @param runWideBudgetRunId 预算范围标识，用于共享预算的子运行
     * @param userId             用户标识
     * @param runContext         运行上下文
     * @param params             LLM参数配置
     * @param sink               SSE事件接收器
     * @return 运行结果
     */
    public AgentRunResult runSubAgentUntilStop(
            String runId,
            String runWideBudgetRunId,
            String userId,
            RunContext runContext,
            LlmParams params,
            AgentEventSink sink
    ) {
        return runUntilStop(runId, runWideBudgetRunId, userId, runContext, params, sink, true);
    }

    /**
     * 执行待确认的工具调用（绕过LLM）。
     * <p>
     * 用于HITL确认流程，当用户确认后直接执行工具而不经过LLM。
     * </p>
     *
     * @param runId     运行标识
     * @param userId    用户标识
     * @param toolName  工具名称
     * @param argsJson  参数JSON
     * @param sink      SSE事件接收器
     * @return 工具执行结果
     */
    public ToolTerminal executePendingConfirmTool(
            String runId,
            String userId,
            String toolName,
            String argsJson,
            AgentEventSink sink
    ) {
        return toolCallCoordinator.executePendingConfirmTool(runId, userId, toolName, argsJson, sink);
    }

    private AgentRunResult runUntilStop(
            String runId,
            String runWideBudgetRunId,
            String userId,
            RunContext runContext,
            LlmParams params,
            AgentEventSink sink,
            boolean subAgentRun
    ) {
        Instant deadline = Instant.now().plusMillis(properties.getAgentLoop().getRunWallclockTimeoutMs());
        int maxTurns = runContext.maxTurns();
        List<Tool> allowedTools = toolCallCoordinator.toolsFromContext(runContext.effectiveAllowedTools());
        String model = runContext.model();
        AgentExecutionBudget.MainTurnBudget mainTurnBudget = subAgentRun ? null : executionBudget.startMainTurn(runId);
        AgentExecutionBudget.SubAgentTurnBudget subAgentTurnBudget = subAgentRun
                ? executionBudget.startSubAgentTurn(runId, runWideBudgetRunId)
                : null;

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

            String attemptId = Ids.newId("att");
            int turnNo = trajectoryStore.nextTurn(runId);
            try (MDC.MDCCloseable ignoredAttempt = MDC.putCloseable("attemptId", attemptId)) {
                ProviderContextView contextView;
                try {
                    contextView = contextViewBuilder.build(
                            runId,
                            turnNo,
                            runContext,
                            () -> reserveLlmCall(mainTurnBudget, subAgentTurnBudget)
                    );
                } catch (LlmCallBudgetExceededException e) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    return pauseForBudgetExceeded(runId, e, sink, subAgentRun);
                } catch (SkillCommandException e) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    return failForSkillCommand(runId, e, sink);
                } catch (Exception e) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    String message = failureMessage(e);
                    AgentRunResult terminal = transitionFromRunning(runId, RunStatus.FAILED, message, null);
                    if (terminal.status() != RunStatus.FAILED) {
                        return terminal;
                    }
                    sink.onError(new ErrorEvent(runId, message));
                    log.error("context view build failed", e);
                    return terminal;
                }
                List<LlmMessage> messages = contextView.messages();
                log.info("llm attempt started turnNo={} messageCount={} model={}", turnNo, messages.size(), model);
                LlmStreamResult result;
                try {
                    result = llmAttemptService.executeAttempt(
                            runId,
                            turnNo,
                            attemptId,
                            runContext,
                            model,
                            params,
                            messages,
                            allowedTools,
                            contextView.compactions(),
                            sink,
                            () -> reserveLlmCall(mainTurnBudget, subAgentTurnBudget)
                    );
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    log.info("llm attempt completed finishReason={} toolCallCount={}", result.finishReason(), result.toolCalls().size());
                } catch (LlmCallBudgetExceededException e) {
                    stopped = stopIfNotRunning(runId);
                    if (stopped != null) {
                        return stopped;
                    }
                    return pauseForBudgetExceeded(runId, e, sink, subAgentRun);
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
                if (toolStep.pendingUserInput() != null) {
                    String finalText = toolStep.pendingUserInput().question();
                    AgentRunResult terminal = transitionFromRunning(
                            runId,
                            RunStatus.PAUSED,
                            "tool needs user input",
                            finalText
                    );
                    if (terminal.status() != RunStatus.PAUSED) {
                        return terminal;
                    }
                    sink.onFinal(new FinalEvent(runId, finalText, RunStatus.PAUSED, "user_input"));
                    log.info("agent run paused because tool needs user input");
                    return terminal;
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

    private void reserveLlmCall(
            AgentExecutionBudget.MainTurnBudget mainTurnBudget,
            AgentExecutionBudget.SubAgentTurnBudget subAgentTurnBudget
    ) {
        if (subAgentTurnBudget != null) {
            executionBudget.reserveSubAgentLlmCall(subAgentTurnBudget);
            return;
        }
        executionBudget.reserveMainLlmCall(mainTurnBudget);
    }

    private AgentRunResult pauseForBudgetExceeded(
            String runId,
            LlmCallBudgetExceededException exception,
            AgentEventSink sink,
            boolean subAgentRun
    ) {
        RunStateMachine.TransitionResult transition = stateMachine.pauseFromRunning(runId, exception.eventType());
        if (!transition.changed()) {
            log.warn("agent budget pause lost race eventType={} currentStatus={}", exception.eventType(), transition.status());
            return new AgentRunResult(runId, transition.status(), null);
        }
        trajectoryStore.writeAgentEvent(runId, exception.eventType(), budgetEventJson(exception));
        String finalText = budgetPauseText(runId, subAgentRun);
        sink.onFinal(new FinalEvent(runId, finalText, RunStatus.PAUSED, "user_input"));
        log.warn(
                "agent run paused because llm budget exceeded eventType={} limit={} used={}",
                exception.eventType(),
                exception.limit(),
                exception.used()
        );
        return new AgentRunResult(runId, RunStatus.PAUSED, finalText);
    }

    private String budgetPauseText(String runId, boolean subAgentRun) {
        if (!subAgentRun) {
            return "LLM 调用预算已达到上限，本轮已暂停。请补充说明后继续。";
        }
        String partialSummary = latestAssistantSummary(runId);
        if (partialSummary.isBlank()) {
            return "SubAgent 已达到 LLM 调用预算，本轮已暂停。当前没有可提取的阶段性结论。";
        }
        return "SubAgent 已达到 LLM 调用预算，本轮已暂停。当前阶段性结论：\n" + partialSummary;
    }

    private String latestAssistantSummary(String runId) {
        try {
            List<LlmMessage> messages = trajectoryReader.loadMessages(runId);
            for (int index = messages.size() - 1; index >= 0; index--) {
                LlmMessage message = messages.get(index);
                if (message.role() == MessageRole.ASSISTANT && message.content() != null && !message.content().isBlank()) {
                    return message.content();
                }
            }
        } catch (RuntimeException e) {
            log.warn("failed to build subagent partial summary runId={}", runId, e);
        }
        return "";
    }

    private String budgetEventJson(LlmCallBudgetExceededException exception) {
        return "{\"eventType\":\"" + exception.eventType()
                + "\",\"limit\":" + exception.limit()
                + ",\"used\":" + exception.used()
                + "}";
    }

    private AgentRunResult failForSkillCommand(String runId, SkillCommandException exception, AgentEventSink sink) {
        AgentRunResult terminal = transitionFromRunning(runId, RunStatus.FAILED, exception.code(), null);
        if (terminal.status() != RunStatus.FAILED) {
            return terminal;
        }
        sink.onError(new ErrorEvent(runId, exception.getMessage(), exception.code(), exception.details()));
        log.warn("agent run failed because slash skill command is invalid code={}", exception.code());
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

    private String failureMessage(Exception failure) {
        if (failure.getMessage() == null || failure.getMessage().isBlank()) {
            return "agent turn failed";
        }
        return failure.getMessage();
    }
}
