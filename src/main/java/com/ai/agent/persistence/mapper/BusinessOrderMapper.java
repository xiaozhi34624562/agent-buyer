package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.BusinessOrderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 业务订单Mapper接口
 * <p>
 * 提供业务订单实体的数据库访问操作，继承MyBatis-Plus的BaseMapper，
 * 并扩展了订单取消功能。
 * </p>
 */
public interface BusinessOrderMapper extends BaseMapper<BusinessOrderEntity> {

    /**
     * 取消指定用户的订单
     * <p>
     * 仅当订单状态为CREATED或PAID时允许取消，取消后状态变为CANCELLED。
     * </p>
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @return 受影响的行数，若订单状态不符合条件则返回0
     */
    @Update("""
            UPDATE business_order
            SET status = 'CANCELLED',
                cancel_reason = 'cancelled_by_agent',
                cancelled_at = CURRENT_TIMESTAMP(3)
            WHERE user_id = #{userId}
              AND order_id = #{orderId}
              AND status IN ('CREATED', 'PAID')
            """)
    int cancelOrder(@Param("userId") String userId, @Param("orderId") String orderId);
}
