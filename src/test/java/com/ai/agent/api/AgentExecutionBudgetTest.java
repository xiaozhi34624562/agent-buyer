package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionBudgetTest {
    @Test
    void perUserTurnBudgetAllowsLimitAndRejectsNextCallWithoutIncrementingRunCounter() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(2);
        properties.getAgentLoop().setRunWideLlmCallBudget(80);
        InMemoryRunLlmCallBudgetStore store = new InMemoryRunLlmCallBudgetStore();
        AgentExecutionBudget budget = new AgentExecutionBudget(properties, store);
        AgentExecutionBudget.MainTurnBudget turnBudget = budget.startMainTurn("run-1");

        budget.reserveMainLlmCall(turnBudget);
        budget.reserveMainLlmCall(turnBudget);

        assertThatThrownBy(() -> budget.reserveMainLlmCall(turnBudget))
                .isInstanceOfSatisfying(LlmCallBudgetExceededException.class, e -> {
                    assertThat(e.eventType()).isEqualTo("MAIN_TURN_BUDGET");
                    assertThat(e.limit()).isEqualTo(2);
                    assertThat(e.used()).isEqualTo(2);
                });
        assertThat(store.countsByRun.get("run-1")).isEqualTo(2);
    }

    @Test
    void runWideBudgetIsSharedAcrossMainTurns() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setLlmCallBudgetPerUserTurn(30);
        properties.getAgentLoop().setRunWideLlmCallBudget(2);
        InMemoryRunLlmCallBudgetStore store = new InMemoryRunLlmCallBudgetStore();
        AgentExecutionBudget budget = new AgentExecutionBudget(properties, store);

        budget.reserveMainLlmCall(budget.startMainTurn("run-1"));
        budget.reserveMainLlmCall(budget.startMainTurn("run-1"));

        assertThatThrownBy(() -> budget.reserveMainLlmCall(budget.startMainTurn("run-1")))
                .isInstanceOfSatisfying(LlmCallBudgetExceededException.class, e -> {
                    assertThat(e.eventType()).isEqualTo("RUN_WIDE_BUDGET");
                    assertThat(e.limit()).isEqualTo(2);
                    assertThat(e.used()).isEqualTo(2);
                });
        assertThat(store.countsByRun.get("run-1")).isEqualTo(2);
    }

    private static final class InMemoryRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
        private final Map<String, Long> countsByRun = new HashMap<>();

        @Override
        public Reservation reserveRunCall(String runId, int limit) {
            long current = countsByRun.getOrDefault(runId, 0L);
            if (current >= limit) {
                return new Reservation(false, current);
            }
            long next = current + 1L;
            countsByRun.put(runId, next);
            return new Reservation(true, next);
        }
    }
}
