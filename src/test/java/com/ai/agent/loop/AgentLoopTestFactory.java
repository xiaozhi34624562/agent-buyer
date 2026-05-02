package com.ai.agent.loop;

import com.ai.agent.application.ConfirmationIntentService;
import com.ai.agent.application.HumanIntentResolver;
import com.ai.agent.application.RunAccessManager;
import com.ai.agent.application.RunStateMachine;
import com.ai.agent.budget.AgentExecutionBudget;
import com.ai.agent.budget.RunLlmCallBudgetStore;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.compact.LargeResultSpiller;
import com.ai.agent.llm.compact.MicroCompactor;
import com.ai.agent.llm.compact.SummaryCompactor;
import com.ai.agent.llm.context.ContextViewBuilder;
import com.ai.agent.llm.context.PromptAssembler;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmProviderAdapterRegistry;
import com.ai.agent.llm.summary.DeterministicSummaryGenerator;
import com.ai.agent.llm.transcript.TranscriptPairValidator;
import com.ai.agent.support.TestObjectProvider;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.RunEventSinkRegistry;
import com.ai.agent.tool.runtime.ToolResultCloser;
import com.ai.agent.tool.runtime.ToolResultWaiter;
import com.ai.agent.tool.runtime.ToolRuntime;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.tool.security.ConfirmTokenStore;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.port.RunContextStore;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;

public final class AgentLoopTestFactory {
    private AgentLoopTestFactory() {
    }

    public static DefaultAgentLoop create(
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
        PendingConfirmToolStore pendingConfirmToolStore = mock(PendingConfirmToolStore.class);
        AgentTurnOrchestrator turnOrchestrator = new AgentTurnOrchestrator(
                properties,
                new ContextViewBuilder(
                        trajectoryReader,
                        transcriptPairValidator,
                        new LargeResultSpiller(properties, new TokenEstimator()),
                        new MicroCompactor(properties, new TokenEstimator()),
                        new SummaryCompactor(
                                properties,
                                new TokenEstimator(),
                                new DeterministicSummaryGenerator(objectMapper),
                                objectMapper
                        )
                ),
                new LlmAttemptService(
                        new LlmProviderAdapterRegistry(List.of(providerAdapter)),
                        trajectoryStore,
                        objectMapper,
                        record -> null
                ),
                new ToolCallCoordinator(
                        properties,
                        toolRegistry,
                        toolRuntime,
                        redisToolStore,
                        toolResultWaiter,
                        trajectoryStore,
                        trajectoryReader,
                        new ToolResultCloser(trajectoryStore, trajectoryReader, TestObjectProvider.empty()),
                        pendingConfirmToolStore,
                        objectMapper
                ),
                trajectoryReader,
                trajectoryStore,
                new RunStateMachine(trajectoryStore),
                new AgentExecutionBudget(properties, new InMemoryRunLlmCallBudgetStore())
        );
        return new DefaultAgentLoop(
                properties,
                promptAssembler,
                toolRegistry,
                trajectoryStore,
                runContextStore,
                sinkRegistry,
                runAccessManager,
                turnOrchestrator,
                new HumanIntentResolver(
                        new ConfirmationIntentService(),
                        (runId, userId, runContext, userMessage) ->
                                HumanIntentResolver.ConfirmationDecision.clarify("请明确回复确认继续执行，或回复放弃本次操作。", "test")
                ),
                confirmTokenStore,
                pendingConfirmToolStore
        );
    }

    private static final class InMemoryRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
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
