package com.ai.agent.business.order;

import com.ai.agent.business.user.UserProfile;
import com.ai.agent.business.user.UserProfileStore;
import com.ai.agent.persistence.entity.BusinessOrderEntity;
import com.ai.agent.persistence.entity.UserProfileEntity;
import com.ai.agent.persistence.mapper.BusinessOrderMapper;
import com.ai.agent.persistence.mapper.UserProfileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于MyBatis的业务客户端实现类
 * <p>
 * 实现OrderClient和UserProfileStore接口，通过MyBatis-Plus进行数据库操作，
 * 提供订单查询、取消预览、订单取消和用户档案查询功能。
 * </p>
 */
@Service
public class MybatisBusinessClient implements OrderClient, UserProfileStore {
    private final BusinessOrderMapper orderMapper;
    private final UserProfileMapper userProfileMapper;

    /**
     * 构造函数
     *
     * @param orderMapper       业务订单Mapper
     * @param userProfileMapper 用户档案Mapper
     */
    public MybatisBusinessClient(BusinessOrderMapper orderMapper, UserProfileMapper userProfileMapper) {
        this.orderMapper = orderMapper;
        this.userProfileMapper = userProfileMapper;
    }

    /**
     * 查询用户订单列表
     * <p>
     * 支持按订单ID、状态、商品名称关键词和日期范围进行筛选，最多返回20条记录。
     * </p>
     *
     * @param userId 用户ID
     * @param query  查询条件
     * @return 符合条件的订单列表
     */
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

    /**
     * 预览订单取消操作
     * <p>
     * 检查订单状态，已取消或已发货的订单不允许取消。
     * </p>
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @return 取消预览结果
     */
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

    /**
     * 执行订单取消操作
     * <p>
     * 先进行取消预检查，若可取消则执行取消并返回结果。
     * 使用事务确保操作的原子性。
     * </p>
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @return 取消结果
     * @throws IllegalStateException 若订单不可取消或取消失败
     */
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

    /**
     * 根据用户ID查询用户档案信息
     * <p>
     * 若用户档案不存在，返回默认的用户档案对象。
     * </p>
     *
     * @param userId 用户ID
     * @return 用户档案信息
     */
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

    /**
     * 查找指定用户的订单
     *
     * @param userId  用户ID
     * @param orderId 订单ID
     * @return 订单对象
     * @throws IllegalArgumentException 若找不到订单
     */
    private Order findOrder(String userId, String orderId) {
        List<Order> orders = queryOrders(userId, new OrderQuery("all", null, null, orderId));
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("找不到当前用户可访问的订单：" + orderId);
        }
        return orders.getFirst();
    }

    /**
     * 将订单实体转换为领域模型对象
     *
     * @param entity 订单实体
     * @return 订单领域模型
     */
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
