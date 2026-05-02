package com.ai.agent.loop;

import com.ai.agent.application.HumanIntentResolver;
import com.ai.agent.application.RunAccessManager;
import com.ai.agent.application.RunStateMachine;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.context.PromptAssembler;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.RunEventSinkRegistry;
import com.ai.agent.tool.security.ConfirmTokenStore;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.RunContextStore;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.dto.LlmParams;
import com.ai.agent.web.dto.UserMessage;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.FinalEvent;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class DefaultAgentLoop implements AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);
    private static final String PRIMARY_PROVIDER = "deepseek";
    private static final String FALLBACK_PROVIDER = "qwen";
    private static final String EMPTY_PROVIDER_OPTIONS = "{}";

    private final AgentProperties properties;
    private final PromptAssembler promptAssembler;
    private final ToolRegistry toolRegistry;
    private final TrajectoryStore trajectoryStore;
    private final RunContextStore runContextStore;
    private final RunEventSinkRegistry sinkRegistry;
    private final RunAccessManager runAccessManager;
    private final RunStateMachine stateMachine;
    private final AgentTurnOrchestrator turnOrchestrator;
    private final HumanIntentResolver humanIntentResolver;
    private final ConfirmTokenStore confirmTokenStore;
    private final PendingConfirmToolStore pendingConfirmToolStore;

    @Autowired
    public DefaultAgentLoop(
            AgentProperties properties,
            PromptAssembler promptAssembler,
            ToolRegistry toolRegistry,
            TrajectoryStore trajectoryStore,
            RunContextStore runContextStore,
            RunEventSinkRegistry sinkRegistry,
            RunAccessManager runAccessManager,
            AgentTurnOrchestrator turnOrchestrator,
            HumanIntentResolver humanIntentResolver,
            ConfirmTokenStore confirmTokenStore,
            PendingConfirmToolStore pendingConfirmToolStore
    ) {
        this.properties = properties;
        this.promptAssembler = promptAssembler;
        this.toolRegistry = toolRegistry;
        this.trajectoryStore = trajectoryStore;
        this.runContextStore = runContextStore;
        this.sinkRegistry = sinkRegistry;
        this.runAccessManager = runAccessManager;
        this.stateMachine = new RunStateMachine(trajectoryStore);
        this.turnOrchestrator = turnOrchestrator;
        this.humanIntentResolver = humanIntentResolver;
        this.confirmTokenStore = confirmTokenStore;
        this.pendingConfirmToolStore = pendingConfirmToolStore;
    }

    @Override
    public AgentRunResult run(String userId, AgentRunRequest request, AgentEventSink sink) {
        String runId = Ids.newId("run");
        try (MDC.MDCCloseable ignoredRun = MDC.putCloseable("runId", runId);
             MDC.MDCCloseable ignoredUser = MDC.putCloseable("userId", userId)) {
            log.info("agent run created messageCount={}", request.messages().size());
            List<Tool> allowedTools = effectiveAllowedTools(request.allowedToolNames());
            RunContext runContext = new RunContext(
                    runId,
                    effectiveAllowedToolNames(allowedTools),
                    effectiveModel(request.llmParams()),
                    PRIMARY_PROVIDER,
                    FALLBACK_PROVIDER,
                    EMPTY_PROVIDER_OPTIONS,
                    effectiveMaxTurns(request.llmParams()),
                    null,
                    null
            );
            createRunAndContext(runId, userId, runContext);
            sinkRegistry.bind(runId, sink);
            try {
                log.info("agent run prompt assembly started allowedToolCount={}", allowedTools.size());
                trajectoryStore.appendMessage(runId, LlmMessage.system(Ids.newId("msg"), promptAssembler.materializeSystemPrompt(userId, allowedTools)));
                for (UserMessage message : request.messages()) {
                    trajectoryStore.appendMessage(runId, LlmMessage.user(Ids.newId("msg"), message.content()));
                }
                stateMachine.startRun(runId);
                return runUntilStop(runId, userId, runContext, request.llmParams(), sink);
            } finally {
                sinkRegistry.unbind(runId);
            }
        }
    }

    @Override
    public AgentRunResult continueRun(String userId, String runId, UserMessage message, AgentEventSink sink) {
        try (MDC.MDCCloseable ignoredRun = MDC.putCloseable("runId", runId);
             MDC.MDCCloseable ignoredUser = MDC.putCloseable("userId", userId)) {
            RunAccessManager.ContinuationPermit permit = runAccessManager.acquireContinuation(userId, runId);
            return continueRunWithPermit(userId, runId, message, sink, permit);
        }
    }

    @Override
    public AgentRunResult continueRun(
            String userId,
            String runId,
            UserMessage message,
            AgentEventSink sink,
            RunAccessManager.ContinuationPermit permit
    ) {
        try (MDC.MDCCloseable ignoredRun = MDC.putCloseable("runId", runId);
             MDC.MDCCloseable ignoredUser = MDC.putCloseable("userId", userId)) {
            return continueRunWithPermit(userId, runId, message, sink, permit);
        }
    }

    private AgentRunResult continueRunWithPermit(
            String userId,
            String runId,
            UserMessage message,
            AgentEventSink sink,
            RunAccessManager.ContinuationPermit permit
    ) {
        if (permit == null || !runId.equals(permit.runId())) {
            throw new RunAccessManager.RunContinuationNotAllowedException(
                    "continuation permit does not match run: " + runId
            );
        }
        sinkRegistry.bind(runId, sink);
        try {
            RunStatus currentStatus = trajectoryStore.findRunStatus(runId);
            if (currentStatus != RunStatus.RUNNING) {
                log.warn("agent continuation skipped because run is no longer RUNNING status={}", currentStatus);
                return new AgentRunResult(runId, currentStatus, null);
            }
            RunContext runContext;
            try {
                runContext = runContextStore.load(runId);
                validateRunContext(runContext);
                trajectoryStore.appendMessage(runId, LlmMessage.user(Ids.newId("msg"), message.content()));
            } catch (RuntimeException e) {
                runAccessManager.restoreWaitingAfterContinuationStartFailure(permit);
                throw e;
            }
            HumanIntentResolver.ConfirmationDecision confirmationDecision = humanIntentResolver.resolveConfirmation(
                    runId,
                    userId,
                    runContext,
                    message.content()
            );
            if (confirmationDecision.intent() == HumanIntentResolver.ConfirmationIntent.REJECT) {
                confirmTokenStore.clearRun(runId);
                pendingConfirmToolStore.clearRun(runId);
                String finalText = "已取消本次操作，订单未被更改。";
                trajectoryStore.appendMessage(runId, LlmMessage.assistant(Ids.newId("msg"), finalText, List.of()));
                AgentRunResult terminal = transitionTerminal(runId, RunStatus.SUCCEEDED, null, finalText);
                if (terminal.status() != RunStatus.SUCCEEDED) {
                    return terminal;
                }
                sink.onFinal(new FinalEvent(runId, finalText, RunStatus.SUCCEEDED, null));
                log.info("agent continuation completed by user rejection");
                return terminal;
            }
            if (confirmationDecision.intent() == HumanIntentResolver.ConfirmationIntent.CLARIFY) {
                String finalText = confirmationDecision.question() == null || confirmationDecision.question().isBlank()
                        ? "请明确回复确认继续执行，或回复放弃本次操作。"
                        : confirmationDecision.question();
                trajectoryStore.appendMessage(runId, LlmMessage.assistant(Ids.newId("msg"), finalText, List.of()));
                AgentRunResult waiting = transitionTerminal(runId, RunStatus.WAITING_USER_CONFIRMATION, null, finalText);
                if (waiting.status() != RunStatus.WAITING_USER_CONFIRMATION) {
                    return waiting;
                }
                sink.onFinal(new FinalEvent(runId, finalText, RunStatus.WAITING_USER_CONFIRMATION, "user_confirmation"));
                log.info("agent continuation remains waiting because user confirmation intent is ambiguous");
                return waiting;
            }
            // CONFIRM intent: inject synthetic tool call with confirmToken and execute
            log.info("agent continuation confirmed - injecting pending tool call");
            PendingConfirmToolStore.PendingConfirmTool pendingTool = pendingConfirmToolStore.consume(runId);
            if (pendingTool != null) {
                // Create synthetic assistant message with pending tool call
                ToolCallMessage syntheticCall = new ToolCallMessage(
                        pendingTool.toolCallId(),
                        pendingTool.toolName(),
                        pendingTool.argsJson()
                );
                trajectoryStore.appendMessage(runId, LlmMessage.assistant(
                        Ids.newId("msg"),
                        null,  // No text content, just tool call
                        List.of(syntheticCall)
                ));
                log.info("synthetic tool call injected toolName={} toolCallId={}", pendingTool.toolName(), pendingTool.toolCallId());
            } else {
                log.warn("no pending tool found for runId={} - proceeding with normal continuation", runId);
            }
            return runUntilStop(runId, userId, runContext, null, sink);
        } finally {
            sinkRegistry.unbind(runId);
            runAccessManager.releaseContinuation(permit);
        }
    }

    private AgentRunResult runUntilStop(
            String runId,
            String userId,
            RunContext runContext,
            LlmParams params,
            AgentEventSink sink
    ) {
        return turnOrchestrator.runUntilStop(runId, userId, runContext, params, sink);
    }

    private void createRunAndContext(String runId, String userId, RunContext runContext) {
        boolean runCreated = false;
        try {
            trajectoryStore.createRun(runId, userId);
            runCreated = true;
            runContextStore.create(runContext);
        } catch (RuntimeException e) {
            if (runCreated) {
                markRunInitializationFailed(runId, e);
            }
            throw e;
        }
    }

    private void markRunInitializationFailed(String runId, RuntimeException cause) {
        try {
            stateMachine.markInitializationFailed(runId, "run context initialization failed");
        } catch (RuntimeException updateFailure) {
            log.warn("failed to mark run initialization failure runId={} cause={}", runId, cause.getMessage(), updateFailure);
        }
    }

    private int effectiveMaxTurns(LlmParams params) {
        return params == null
                ? properties.getAgentLoop().getMaxTurns()
                : params.effectiveMaxTurns(properties.getAgentLoop().getMaxTurns());
    }

    private String effectiveModel(LlmParams params) {
        if (params != null && params.model() != null && !params.model().isBlank()) {
            return params.model();
        }
        return properties.getLlm().getDeepseek().getDefaultModel();
    }

    private List<Tool> effectiveAllowedTools(Set<String> allowedToolNames) {
        Map<String, Tool> defaultAllowed = properties.getDefaultAllowedTools().stream()
                .map(toolRegistry::resolve)
                .collect(Collectors.toMap(
                        tool -> ToolRegistry.canonicalize(tool.schema().name()),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        if (allowedToolNames == null) {
            return defaultAllowed.values().stream()
                    .sorted(Comparator.comparing(tool -> tool.schema().name()))
                    .toList();
        }
        Set<String> requested = allowedToolNames.stream()
                .map(ToolRegistry::canonicalize)
                .collect(Collectors.toSet());
        return defaultAllowed.entrySet().stream()
                .filter(entry -> requested.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(tool -> tool.schema().name()))
                .toList();
    }

    private List<String> effectiveAllowedToolNames(List<Tool> allowedTools) {
        return allowedTools.stream()
                .map(tool -> tool.schema().name())
                .toList();
    }

    private List<Tool> toolsFromContext(RunContext runContext) {
        return runContext.effectiveAllowedTools().stream()
                .map(toolRegistry::resolve)
                .toList();
    }

    private void validateRunContext(RunContext runContext) {
        if (runContext.model() == null || runContext.model().isBlank()) {
            throw new IllegalStateException("run context model missing: " + runContext.runId());
        }
        if (runContext.primaryProvider() == null || runContext.primaryProvider().isBlank()) {
            throw new IllegalStateException("run context primaryProvider missing: " + runContext.runId());
        }
        if (runContext.fallbackProvider() == null || runContext.fallbackProvider().isBlank()) {
            throw new IllegalStateException("run context fallbackProvider missing: " + runContext.runId());
        }
        if (runContext.providerOptions() == null || runContext.providerOptions().isBlank()) {
            throw new IllegalStateException("run context providerOptions missing: " + runContext.runId());
        }
        if (runContext.maxTurns() <= 0) {
            throw new IllegalStateException("run context maxTurns invalid: " + runContext.runId());
        }
        toolsFromContext(runContext);
    }

    private AgentRunResult transitionTerminal(String runId, RunStatus status, String error, String finalText) {
        RunStateMachine.TransitionResult result = stateMachine.completeFromRunning(runId, status, error);
        if (result.changed()) {
            return new AgentRunResult(runId, status, finalText);
        }
        RunStatus current = result.status();
        log.warn("agent terminal transition lost race targetStatus={} currentStatus={}", status, current);
        return new AgentRunResult(runId, current, null);
    }
}
