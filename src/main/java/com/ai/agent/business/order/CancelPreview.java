package com.ai.agent.business.order;

/**
 * 订单取消预览记录
 * <p>
 * 用于展示订单取消前的预检查结果，包含是否可取消、取消摘要说明及原因标识。
 * </p>
 *
 * @param orderId    订单ID
 * @param cancellable 是否可取消
 * @param summary    取消操作摘要说明
 * @param reason     原因标识，如"ok"、"already_cancelled"、"already_shipped"
 */
public record CancelPreview(
        String orderId,
        boolean cancellable,
        String summary,
        String reason
) {
}
