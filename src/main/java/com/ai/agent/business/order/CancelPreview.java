package com.ai.agent.business.order;

public record CancelPreview(
        String orderId,
        boolean cancellable,
        String summary,
        String reason
) {
}
