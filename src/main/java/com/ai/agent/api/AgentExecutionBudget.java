package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

@Component
public final class AgentExecutionBudget {
    public static final String MAIN_TURN_BUDGET = "MAIN_TURN_BUDGET";
    public static final String SUB_TURN_BUDGET = "SUB_TURN_BUDGET";
    public static final String RUN_WIDE_BUDGET = "RUN_WIDE_BUDGET";

    private final AgentProperties properties;
    private final RunLlmCallBudgetStore budgetStore;

    public AgentExecutionBudget(AgentProperties properties, RunLlmCallBudgetStore budgetStore) {
        this.properties = properties;
        this.budgetStore = budgetStore;
    }

    public MainTurnBudget startMainTurn(String runId) {
        return new MainTurnBudget(runId, runId, properties.getAgentLoop().getLlmCallBudgetPerUserTurn());
    }

    public SubAgentTurnBudget startSubAgentTurn(String runId) {
        return startSubAgentTurn(runId, runId);
    }

    public SubAgentTurnBudget startSubAgentTurn(String runId, String runWideBudgetRunId) {
        return new SubAgentTurnBudget(
                runId,
                runWideBudgetRunId,
                properties.getAgentLoop().getSubAgentLlmCallBudgetPerUserTurn()
        );
    }

    public void reserveMainLlmCall(MainTurnBudget budget) {
        reserveTurnLlmCall(budget, MAIN_TURN_BUDGET);
    }

    public void reserveSubAgentLlmCall(SubAgentTurnBudget budget) {
        reserveTurnLlmCall(budget, SUB_TURN_BUDGET);
    }

    private void reserveTurnLlmCall(TurnBudget budget, String turnBudgetEventType) {
        if (budget.usedInTurn >= budget.turnLimit) {
            throw new LlmCallBudgetExceededException(turnBudgetEventType, budget.turnLimit, budget.usedInTurn);
        }
        int runWideLimit = properties.getAgentLoop().getRunWideLlmCallBudget();
        RunLlmCallBudgetStore.Reservation reservation = budgetStore.reserveRunCall(budget.runWideBudgetRunId, runWideLimit);
        if (!reservation.accepted()) {
            throw new LlmCallBudgetExceededException(RUN_WIDE_BUDGET, runWideLimit, reservation.used());
        }
        budget.usedInTurn++;
    }

    private abstract static class TurnBudget {
        private final String runId;
        private final String runWideBudgetRunId;
        private final int turnLimit;
        private int usedInTurn;

        private TurnBudget(String runId, String runWideBudgetRunId, int turnLimit) {
            this.runId = runId;
            this.runWideBudgetRunId = runWideBudgetRunId;
            this.turnLimit = turnLimit;
        }
    }

    public static final class MainTurnBudget extends TurnBudget {
        private MainTurnBudget(String runId, String runWideBudgetRunId, int turnLimit) {
            super(runId, runWideBudgetRunId, turnLimit);
        }
    }

    public static final class SubAgentTurnBudget extends TurnBudget {
        private SubAgentTurnBudget(String runId, String runWideBudgetRunId, int turnLimit) {
            super(runId, runWideBudgetRunId, turnLimit);
        }
    }
}
