package com.ai.agent.business;

import com.ai.agent.persistence.entity.BusinessOrderEntity;
import com.ai.agent.persistence.entity.UserProfileEntity;
import com.ai.agent.persistence.mapper.BusinessOrderMapper;
import com.ai.agent.persistence.mapper.UserProfileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class MybatisBusinessClient implements OrderClient, UserProfileStore {
    private final BusinessOrderMapper orderMapper;
    private final UserProfileMapper userProfileMapper;

    public MybatisBusinessClient(BusinessOrderMapper orderMapper, UserProfileMapper userProfileMapper) {
        this.orderMapper = orderMapper;
        this.userProfileMapper = userProfileMapper;
    }

    @Override
    public List<Order> queryOrders(String userId, OrderQuery query) {
        var wrapper = Wrappers.lambdaQuery(BusinessOrderEntity.class)
                .eq(BusinessOrderEntity::getUserId, userId);

        if (query.orderId() != null && !query.orderId().isBlank()) {
            wrapper.eq(BusinessOrderEntity::getOrderId, query.orderId());
        }
        if (query.status() != null) {
            wrapper.eq(BusinessOrderEntity::getStatus, query.status().name());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            wrapper.like(BusinessOrderEntity::getItemName, query.keyword().trim());
        }
        if ("yesterday".equalsIgnoreCase(query.dateRange())) {
            LocalDate today = LocalDate.now();
            wrapper.ge(BusinessOrderEntity::getCreatedAt, today.minusDays(1).atStartOfDay())
                    .lt(BusinessOrderEntity::getCreatedAt, today.atStartOfDay());
        } else if ("today".equalsIgnoreCase(query.dateRange())) {
            wrapper.ge(BusinessOrderEntity::getCreatedAt, LocalDate.now().atStartOfDay());
        } else if ("last_7_days".equalsIgnoreCase(query.dateRange())) {
            wrapper.ge(BusinessOrderEntity::getCreatedAt, LocalDateTime.now().minusDays(7));
        }

        wrapper.orderByDesc(BusinessOrderEntity::getCreatedAt).last("LIMIT 20");
        return orderMapper.selectList(wrapper).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public CancelPreview previewCancelOrder(String userId, String orderId) {
        Order order = findOrder(userId, orderId);
        if (order.status() == OrderStatus.CANCELLED) {
            return new CancelPreview(orderId, false, "订单 " + orderId + " 已经取消，无需重复操作。", "already_cancelled");
        }
        if (order.status() == OrderStatus.SHIPPED) {
            return new CancelPreview(orderId, false, "订单 " + orderId + " 已发货，不能直接取消。", "already_shipped");
        }
        return new CancelPreview(
                orderId,
                true,
                "将取消订单 " + orderId + "（" + order.itemName() + "，金额 " + order.amount() + "）。",
                "ok"
        );
    }

    @Override
    @Transactional
    public CancelResult cancelOrder(String userId, String orderId) {
        CancelPreview preview = previewCancelOrder(userId, orderId);
        if (!preview.cancellable()) {
            throw new IllegalStateException(preview.summary());
        }
        int updated = orderMapper.cancelOrder(userId, orderId);
        if (updated != 1) {
            throw new IllegalStateException("订单状态已变化，取消失败。");
        }
        return new CancelResult(orderId, OrderStatus.CANCELLED, "订单 " + orderId + " 已取消。");
    }

    @Override
    public UserProfile findByUserId(String userId) {
        UserProfileEntity entity = userProfileMapper.selectById(userId);
        if (entity == null) {
            return new UserProfile(userId, userId, null, null, null, "buyer");
        }
        return new UserProfile(
                entity.getUserId(),
                entity.getDisplayName(),
                entity.getPhone(),
                entity.getEmail(),
                entity.getAddress(),
                entity.getRoleName()
        );
    }

    private Order findOrder(String userId, String orderId) {
        List<Order> orders = queryOrders(userId, new OrderQuery("all", null, null, orderId));
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("找不到当前用户可访问的订单：" + orderId);
        }
        return orders.getFirst();
    }

    private Order toDomain(BusinessOrderEntity entity) {
        return new Order(
                entity.getOrderId(),
                entity.getUserId(),
                OrderStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                entity.getAmount(),
                entity.getItemName()
        );
    }
}
