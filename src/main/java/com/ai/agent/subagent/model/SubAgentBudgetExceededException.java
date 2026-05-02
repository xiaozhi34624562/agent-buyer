package com.ai.agent.subagent.model;

import com.ai.agent.subagent.runtime.SubAgentBudgetPolicy;

/**
 * 子代理预算超限异常。
 * <p>
 * 当子代理资源预留被拒绝时抛出，包含预留结果详情。
 * </p>
 */
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
