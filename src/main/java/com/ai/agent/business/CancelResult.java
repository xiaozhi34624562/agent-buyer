package com.ai.agent.business;

public record CancelResult(
        String orderId,
        OrderStatus status,
        String summary
) {
}
