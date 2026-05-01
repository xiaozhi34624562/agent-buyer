package com.ai.agent.persistence.mapper;

import com.ai.agent.persistence.entity.BusinessOrderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BusinessOrderMapper extends BaseMapper<BusinessOrderEntity> {
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
