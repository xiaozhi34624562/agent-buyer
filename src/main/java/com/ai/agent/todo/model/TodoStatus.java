package com.ai.agent.todo.model;

/**
 * Todo 步骤状态枚举。
 */
public enum TodoStatus {
    /** 待处理 */
    PENDING,
    /** 进行中 */
    IN_PROGRESS,
    /** 已完成 */
    DONE,
    /** 已阻塞 */
    BLOCKED,
    /** 已取消 */
    CANCELLED;

    /**
     * 判断状态是否为开放状态（未完成且未取消）。
     *
     * @return 是否开放
     */
    public boolean open() {
        return this != DONE && this != CANCELLED;
    }
}
