package com.ai.agent.application;

import com.ai.agent.application.HumanIntentResolver.ConfirmationDecision;
import com.ai.agent.trajectory.model.RunContext;

/**
 * 语义确认意图分类器接口。
 * 用于分析用户消息，判断用户是否确认执行待处理的高风险操作。
 */
public interface SemanticConfirmationIntentClassifier {
    /**
     * 分类用户消息的确认意图。
     *
     * @param runId      运行ID
     * @param userId     用户ID
     * @param runContext 运行上下文
     * @param userMessage 用户消息内容
     * @return 确认决策结果
     */
    ConfirmationDecision classify(String runId, String userId, RunContext runContext, String userMessage);
}
