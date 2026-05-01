package com.ai.agent.business.order;

public record OrderQuery(
        String dateRange,
        OrderStatus status,
        String keyword,
        String orderId
) {
}
