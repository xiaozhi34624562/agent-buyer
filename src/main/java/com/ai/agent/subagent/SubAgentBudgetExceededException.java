package com.ai.agent.subagent;

public final class SubAgentBudgetExceededException extends RuntimeException {
    private final ReserveChildResult reserveResult;

    public SubAgentBudgetExceededException(ReserveChildResult reserveResult) {
        super(SubAgentBudgetPolicy.SUBAGENT_BUDGET_EXCEEDED + ": " + reserveResult.reason());
        this.reserveResult = reserveResult;
    }

    public ReserveChildResult reserveResult() {
        return reserveResult;
    }
}
