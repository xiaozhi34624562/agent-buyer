package com.ai.agent.budget;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

/**
 * Agent执行预算管理器
 *
 * <p>管理Agent执行过程中的LLM调用预算，包括主轮次预算、子Agent预算和全局运行预算三个层级</p>
 */
@Component
public final class AgentExecutionBudget {
    /** 主轮次预算事件类型 */
    public static final String MAIN_TURN_BUDGET = "MAIN_TURN_BUDGET";
    /** 子Agent轮次预算事件类型 */
    public static final String SUB_TURN_BUDGET = "SUB_TURN_BUDGET";
    /** 全局运行预算事件类型 */
    public static final String RUN_WIDE_BUDGET = "RUN_WIDE_BUDGET";

    private final AgentProperties properties;
    private final RunLlmCallBudgetStore budgetStore;

    /**
     * 构造预算管理器
     *
     * @param properties Agent配置属性
     * @param budgetStore 预算存储实现
     */
    public AgentExecutionBudget(AgentProperties properties, RunLlmCallBudgetStore budgetStore) {
        this.properties = properties;
        this.budgetStore = budgetStore;
    }

    /**
     * 启动主轮次预算
     *
     * @param runId 运行ID
     * @return 主轮次预算对象
     */
    public MainTurnBudget startMainTurn(String runId) {
        return new MainTurnBudget(runId, runId, properties.getAgentLoop().getLlmCallBudgetPerUserTurn());
    }

    /**
     * 启动子Agent轮次预算
     *
     * @param runId 运行ID
     * @return 子Agent轮次预算对象
     */
    public SubAgentTurnBudget startSubAgentTurn(String runId) {
        return startSubAgentTurn(runId, runId);
    }

    /**
     * 启动子Agent轮次预算（指定全局预算运行ID）
     *
     * @param runId 当前运行ID
     * @param runWideBudgetRunId 全局预算关联的运行ID
     * @return 子Agent轮次预算对象
     */
    public SubAgentTurnBudget startSubAgentTurn(String runId, String runWideBudgetRunId) {
        return new SubAgentTurnBudget(
                runId,
                runWideBudgetRunId,
                properties.getAgentLoop().getSubAgentLlmCallBudgetPerUserTurn()
        );
    }

    /**
     * 预留主轮次LLM调用
     *
     * @param budget 主轮次预算对象
     * @throws LlmCallBudgetExceededException 当预算超限时抛出
     */
    public void reserveMainLlmCall(MainTurnBudget budget) {
        reserveTurnLlmCall(budget, MAIN_TURN_BUDGET);
    }

    /**
     * 预留子Agent LLM调用
     *
     * @param budget 子Agent轮次预算对象
     * @throws LlmCallBudgetExceededException 当预算超限时抛出
     */
    public void reserveSubAgentLlmCall(SubAgentTurnBudget budget) {
        reserveTurnLlmCall(budget, SUB_TURN_BUDGET);
    }

    /**
     * 预留轮次LLM调用
     *
     * <p>检查轮次预算和全局预算，超限时抛出异常</p>
     *
     * @param budget 轮次预算对象
     * @param turnBudgetEventType 预算事件类型
     * @throws LlmCallBudgetExceededException 当预算超限时抛出
     */
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

    /**
     * 轮次预算抽象基类
     *
     * <p>封装轮次预算的通用属性</p>
     */
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

    /**
     * 主轮次预算
     *
     * <p>用于管理主Agent轮次的LLM调用预算</p>
     */
    public static final class MainTurnBudget extends TurnBudget {
        private MainTurnBudget(String runId, String runWideBudgetRunId, int turnLimit) {
            super(runId, runWideBudgetRunId, turnLimit);
        }
    }

    /**
     * 子Agent轮次预算
     *
     * <p>用于管理子Agent轮次的LLM调用预算</p>
     */
    public static final class SubAgentTurnBudget extends TurnBudget {
        private SubAgentTurnBudget(String runId, String runWideBudgetRunId, int turnLimit) {
            super(runId, runWideBudgetRunId, turnLimit);
        }
    }
}