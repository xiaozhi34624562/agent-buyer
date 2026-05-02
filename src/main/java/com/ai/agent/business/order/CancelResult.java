package com.ai.agent.business.order;

/**
 * 订单取消结果记录
 * <p>
 * 表示订单取消操作执行后的结果信息。
 * </p>
 *
 * @param orderId 订单ID
 * @param status  取消后的订单状态
 * @param summary 取消结果摘要说明
 */
public record CancelResult(
        String orderId,
        OrderStatus status,
        String summary
) {
}
