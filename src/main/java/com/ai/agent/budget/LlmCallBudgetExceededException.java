package com.ai.agent.budget;

public final class LlmCallBudgetExceededException extends RuntimeException {
    private final String eventType;
    private final int limit;
    private final long used;

    public LlmCallBudgetExceededException(String eventType, int limit, long used) {
        super(eventType + " exceeded");
        this.eventType = eventType;
        this.limit = limit;
        this.used = used;
    }

    public String eventType() {
        return eventType;
    }

    public int limit() {
        return limit;
    }

    public long used() {
        return used;
    }
}
