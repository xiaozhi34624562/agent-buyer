package com.ai.agent.business;

public record OrderQuery(
        String dateRange,
        OrderStatus status,
        String keyword,
        String orderId
) {
}
