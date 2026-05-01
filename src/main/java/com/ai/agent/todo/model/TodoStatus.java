package com.ai.agent.todo.model;

public enum TodoStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    BLOCKED,
    CANCELLED;

    public boolean open() {
        return this != DONE && this != CANCELLED;
    }
}
