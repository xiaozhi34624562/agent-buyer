package com.ai.agent.budget;

/**
 * LLM调用预算超限异常
 *
 * <p>当LLM调用次数超过预设预算限制时抛出此异常，用于控制资源消耗</p>
 */
public final class LlmCallBudgetExceededException extends RuntimeException {
    private final String eventType;
    private final int limit;
    private final long used;

    /**
     * 构造预算超限异常
     *
     * @param eventType 事件类型，标识超限的预算类型
     * @param limit 预算上限
     * @param used 实际已使用量
     */
    public LlmCallBudgetExceededException(String eventType, int limit, long used) {
        super(eventType + " exceeded");
        this.eventType = eventType;
        this.limit = limit;
        this.used = used;
    }

    /**
     * 获取事件类型
     *
     * @return 事件类型名称
     */
    public String eventType() {
        return eventType;
    }

    /**
     * 获取预算上限
     *
     * @return 预算上限值
     */
    public int limit() {
        return limit;
    }

    /**
     * 获取已使用量
     *
     * @return 已使用的调用次数
     */
    public long used() {
        return used;
    }
}