package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.ContextViewBuilder;
import com.ai.agent.llm.DeterministicSummaryGenerator;
import com.ai.agent.llm.LargeResultSpiller;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.MicroCompactor;
import com.ai.agent.llm.PromptAssembler;
import com.ai.agent.llm.SummaryCompactor;
import com.ai.agent.llm.TokenEstimator;
import com.ai.agent.llm.TranscriptPairValidator;
import com.ai.agent.tool.ConfirmTokenStore;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolResultWaiter;
import com.ai.agent.tool.ToolRuntime;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class AgentLoopTestFactory {
    private AgentLoopTestFactory() {
    }

    static DefaultAgentLoop create(
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
                        new ToolResultCloser(trajectoryStore, trajectoryReader),
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
                new ConfirmationIntentService(),
                confirmTokenStore
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
