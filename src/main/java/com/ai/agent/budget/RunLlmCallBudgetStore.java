package com.ai.agent.budget;

/**
 * LLM调用预算存储接口
 *
 * <p>定义LLM调用次数预算的存储和预留机制，支持分布式环境下的预算管理</p>
 */
public interface RunLlmCallBudgetStore {

    /**
     * 预留一次LLM调用
     *
     * @param runId 运行ID，用于标识一次Agent运行
     * @param limit 调用次数上限
     * @return 预留结果，包含是否被接受及当前已使用次数
     */
    Reservation reserveRunCall(String runId, int limit);

    /**
     * 预留结果记录
     *
     * <p>封装预留操作的结果信息</p>
     *
     * @param accepted 是否接受本次调用（未超限返回true）
     * @param used 当前已使用的调用次数
     */
    record Reservation(boolean accepted, long used) {
    }
}