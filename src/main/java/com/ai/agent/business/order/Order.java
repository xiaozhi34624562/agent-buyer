package com.ai.agent.business.order;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(
        String orderId,
        String userId,
        OrderStatus status,
        Instant createdAt,
        BigDecimal amount,
        String itemName
) {
}
