package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.LargeResultSpiller;
import com.ai.agent.llm.PromptAssembler;
import com.ai.agent.llm.ContextViewBuilder;
import com.ai.agent.llm.TokenEstimator;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.tool.ConfirmTokenStore;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.Tool;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final ConfirmationIntentService confirmationIntentService;
    private final ConfirmTokenStore confirmTokenStore;

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
            ConfirmationIntentService confirmationIntentService,
            ConfirmTokenStore confirmTokenStore
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
        this.confirmationIntentService = confirmationIntentService;
        this.confirmTokenStore = confirmTokenStore;
    }

    DefaultAgentLoop(
            AgentProperties properties,
            PromptAssembler promptAssembler,
            LlmProviderAdapter providerAdapter,
            TranscriptPairValidator transcriptPairValidator,
            ToolRegistry toolRegistry,
            ToolRuntime toolRuntime,
            RedisToolStore redisToolStore,
            ToolResultWaiter toolResultWaiter,
            TrajectoryStore trajectoryStore,
            TrajectoryReader trajectoryReader,
            RunContextStore runContextStore,
            RunEventSinkRegistry sinkRegistry,
            RunAccessManager runAccessManager,
            ConfirmTokenStore confirmTokenStore,
            ObjectMapper objectMapper
    ) {
        this(
                properties,
                promptAssembler,
                toolRegistry,
                trajectoryStore,
                runContextStore,
                sinkRegistry,
                runAccessManager,
                new AgentTurnOrchestrator(
                        properties,
                        new ContextViewBuilder(
                                trajectoryReader,
                                transcriptPairValidator,
                                new LargeResultSpiller(properties, new TokenEstimator())
                        ),
                        new LlmAttemptService(new LlmProviderAdapterRegistry(List.of(providerAdapter)), trajectoryStore, objectMapper),
                        new ToolCallCoordinator(
                                properties,
                                toolRegistry,
                                toolRuntime,
                                redisToolStore,
                                toolResultWaiter,
                                trajectoryStore,
                                trajectoryReader,
                                new ToolResultCloser(trajectoryStore, trajectoryReader),
                                objectMapper
                        ),
                        trajectoryStore,
                        new RunStateMachine(trajectoryStore),
                        new AgentExecutionBudget(properties, new LocalRunLlmCallBudgetStore())
                ),
                new ConfirmationIntentService(),
                confirmTokenStore
        );
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
            ConfirmationIntentService.ConfirmationIntent confirmationIntent = confirmationIntentService.classify(message.content());
            if (confirmationIntent == ConfirmationIntentService.ConfirmationIntent.REJECT) {
                confirmTokenStore.clearRun(runId);
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
            if (confirmationIntent == ConfirmationIntentService.ConfirmationIntent.AMBIGUOUS) {
                String finalText = "请明确回复确认取消或放弃操作。";
                trajectoryStore.appendMessage(runId, LlmMessage.assistant(Ids.newId("msg"), finalText, List.of()));
                AgentRunResult waiting = transitionTerminal(runId, RunStatus.WAITING_USER_CONFIRMATION, null, finalText);
                if (waiting.status() != RunStatus.WAITING_USER_CONFIRMATION) {
                    return waiting;
                }
                sink.onFinal(new FinalEvent(runId, finalText, RunStatus.WAITING_USER_CONFIRMATION, "user_confirmation"));
                log.info("agent continuation remains waiting because user confirmation intent is ambiguous");
                return waiting;
            }
            log.info("agent continuation resumed");
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

    private static final class LocalRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
        private final Map<String, Long> countsByRun = new ConcurrentHashMap<>();

        @Override
        public Reservation reserveRunCall(String runId, int limit) {
            AtomicBoolean accepted = new AtomicBoolean(false);
            long next = countsByRun.compute(runId, (ignored, current) -> {
                long used = current == null ? 0L : current;
                if (used >= limit) {
                    return used;
                }
                accepted.set(true);
                return used + 1L;
            });
            return new Reservation(accepted.get(), next);
        }
    }
}
