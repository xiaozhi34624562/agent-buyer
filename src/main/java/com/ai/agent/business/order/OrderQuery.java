package com.ai.agent.business.order;

/**
 * 订单查询条件记录
 * <p>
 * 用于构建订单查询的筛选条件，支持按日期范围、状态、关键词和订单ID进行筛选。
 * </p>
 *
 * @param dateRange 日期范围，如"today"、"yesterday"、"last_7_days"
 * @param status    订单状态筛选条件
 * @param keyword   商品名称关键词
 * @param orderId   订单ID精确筛选
 */
public record OrderQuery(
        String dateRange,
        OrderStatus status,
        String keyword,
        String orderId
) {
}
