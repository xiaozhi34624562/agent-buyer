package com.ai.agent.business.order;

import java.util.List;

/**
 * 订单业务客户端接口
 * <p>
 * 定义订单业务操作的核心接口，包括订单查询、取消预览和订单取消功能。
 * </p>
 */
public interface OrderClient {

    /**
     * 查询用户订单列表
     *
     * @param userId 用户ID
     * @param query  查询条件
     * @return 符合条件的订单列表
     */
    List<Order> queryOrders(String userId, OrderQuery query);

    /**
     * 预览订单取消操作
     * <p>
     * 在实际取消前检查订单是否可取消，并返回取消预览信息。
     * </p>
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @return 取消预览结果
     */
    CancelPreview previewCancelOrder(String userId, String orderId);

    /**
     * 执行订单取消操作
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @return 取消结果
     */
    CancelResult cancelOrder(String userId, String orderId);
}
