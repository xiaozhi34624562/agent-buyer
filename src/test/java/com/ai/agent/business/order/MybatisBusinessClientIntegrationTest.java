package com.ai.agent.business.order;

import com.ai.agent.persistence.entity.BusinessOrderEntity;
import com.ai.agent.persistence.mapper.BusinessOrderMapper;
import com.ai.agent.util.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MybatisBusinessClientIntegrationTest {
    @Autowired
    BusinessOrderMapper orderMapper;

    @Autowired
    OrderClient orderClient;

    @Test
    void queriesAndCancelsOrderThroughMybatisMappers() {
        String orderId = Ids.newId("O_TEST");
        BusinessOrderEntity entity = new BusinessOrderEntity();
        entity.setOrderId(orderId);
        entity.setUserId("demo-user");
        entity.setStatus(OrderStatus.PAID.name());
        entity.setCreatedAt(LocalDateTime.now().minusDays(1));
        entity.setAmount(new BigDecimal("12.34"));
        entity.setItemName("Integration Test Item");
        orderMapper.insert(entity);

        var orders = orderClient.queryOrders("demo-user", new OrderQuery("all", null, null, orderId));
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst().orderId()).isEqualTo(orderId);

        var preview = orderClient.previewCancelOrder("demo-user", orderId);
        assertThat(preview.cancellable()).isTrue();

        var result = orderClient.cancelOrder("demo-user", orderId);
        assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    }
}
