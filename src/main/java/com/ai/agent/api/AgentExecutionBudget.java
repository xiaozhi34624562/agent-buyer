package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

@Component
public final class AgentExecutionBudget {
    public static final String MAIN_TURN_BUDGET = "MAIN_TURN_BUDGET";
    public static final String RUN_WIDE_BUDGET = "RUN_WIDE_BUDGET";

    private final AgentProperties properties;
    private final RunLlmCallBudgetStore budgetStore;

    public AgentExecutionBudget(AgentProperties properties, RunLlmCallBudgetStore budgetStore) {
        this.properties = properties;
        this.budgetStore = budgetStore;
    }

    public MainTurnBudget startMainTurn(String runId) {
        return new MainTurnBudget(runId, properties.getAgentLoop().getLlmCallBudgetPerUserTurn());
    }

    public void reserveMainLlmCall(MainTurnBudget budget) {
        if (budget.usedInTurn >= budget.turnLimit) {
            throw new LlmCallBudgetExceededException(MAIN_TURN_BUDGET, budget.turnLimit, budget.usedInTurn);
        }
        int runWideLimit = properties.getAgentLoop().getRunWideLlmCallBudget();
        RunLlmCallBudgetStore.Reservation reservation = budgetStore.reserveRunCall(budget.runId, runWideLimit);
        if (!reservation.accepted()) {
            throw new LlmCallBudgetExceededException(RUN_WIDE_BUDGET, runWideLimit, reservation.used());
        }
        budget.usedInTurn++;
    }

    public static final class MainTurnBudget {
        private final String runId;
        private final int turnLimit;
        private int usedInTurn;

        private MainTurnBudget(String runId, int turnLimit) {
            this.runId = runId;
            this.turnLimit = turnLimit;
        }
    }
}
