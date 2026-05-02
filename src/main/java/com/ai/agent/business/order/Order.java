package com.ai.agent.business.order;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单领域模型记录
 * <p>
 * 表示业务订单的核心信息，包括订单ID、用户ID、状态、创建时间、金额和商品名称。
 * </p>
 *
 * @param orderId   订单ID
 * @param userId    用户ID
 * @param status    订单状态
 * @param createdAt 创建时间
 * @param amount    订单金额
 * @param itemName  商品名称
 */
public record Order(
        String orderId,
        String userId,
        OrderStatus status,
        Instant createdAt,
        BigDecimal amount,
        String itemName
) {
}
