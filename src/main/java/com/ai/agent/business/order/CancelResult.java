package com.ai.agent.business.order;

public record CancelResult(
        String orderId,
        OrderStatus status,
        String summary
) {
}
