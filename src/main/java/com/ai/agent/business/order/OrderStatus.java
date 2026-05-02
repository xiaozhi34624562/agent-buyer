package com.ai.agent.business.order;

/**
 * 订单状态枚举
 * <p>
 * 定义订单的生命周期状态，包括创建、已支付、已发货和已取消。
 * </p>
 */
public enum OrderStatus {
    /**
     * 已创建状态
     */
    CREATED,
    /**
     * 已支付状态
     */
    PAID,
    /**
     * 已发货状态
     */
    SHIPPED,
    /**
     * 已取消状态
     */
    CANCELLED
}
