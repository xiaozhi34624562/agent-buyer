package com.ai.agent.business.order;

import java.util.List;

public interface OrderClient {
    List<Order> queryOrders(String userId, OrderQuery query);

    CancelPreview previewCancelOrder(String userId, String orderId);

    CancelResult cancelOrder(String userId, String orderId);
}
