package com.ai.agent.business;

public record CancelPreview(
        String orderId,
        boolean cancellable,
        String summary,
        String reason
) {
}
