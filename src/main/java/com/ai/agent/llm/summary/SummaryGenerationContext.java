package com.ai.agent.llm.summary;

import com.ai.agent.llm.provider.LlmCallObserver;
import com.ai.agent.trajectory.model.RunContext;

/**
 * 摘要生成上下文。
 * 包含摘要生成所需的运行信息和调用观察者。
 *
 * @param runId       运行ID
 * @param turnNo      轮次编号
 * @param runContext  运行上下文
 * @param callObserver LLM调用观察者
 */
public record SummaryGenerationContext(
        String runId,
        int turnNo,
        RunContext runContext,
        LlmCallObserver callObserver
) {
    /**
     * 创建简化版摘要生成上下文。
     *
     * @param runId       运行ID
     * @param turnNo      轮次编号
     * @param callObserver LLM调用观察者
     */
    public SummaryGenerationContext(String runId, int turnNo, LlmCallObserver callObserver) {
        this(runId, turnNo, null, callObserver);
    }

    /**
     * 紧凑构造器，确保callObserver非空。
     */
    public SummaryGenerationContext {
        callObserver = callObserver == null ? LlmCallObserver.NOOP : callObserver;
    }
}
