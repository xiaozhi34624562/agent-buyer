package com.ai.agent.tool.builtin.order;

import com.ai.agent.business.order.Order;
import com.ai.agent.business.order.OrderClient;
import com.ai.agent.business.order.OrderQuery;
import com.ai.agent.business.order.OrderStatus;
import com.ai.agent.tool.core.AbstractTool;
import com.ai.agent.tool.core.CancellationToken;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.PiiMasker;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 查询订单工具。
 * 根据日期范围、状态、关键词或订单ID查询用户订单。
 */
@Component
public final class QueryOrderTool extends AbstractTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "dateRange": {
                  "type": "string",
                  "enum": ["today", "yesterday", "last_7_days", "all"],
                  "description": "Relative order creation time filter. Use yesterday when the user says yesterday."
                },
                "status": {
                  "type": "string",
                  "enum": ["CREATED", "PAID", "SHIPPED", "CANCELLED"]
                },
                "keyword": {"type": "string"},
                "orderId": {"type": "string"}
              },
              "additionalProperties": false
            }
            """;

    private final OrderClient orderClient;

    public QueryOrderTool(PiiMasker piiMasker, OrderClient orderClient, ObjectMapper objectMapper) {
        super(piiMasker, objectMapper);
        this.orderClient = orderClient;
    }

    /**
     * 获取工具Schema定义。
     *
     * @return 工具Schema
     */
    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "query_order",
                "Query orders visible to the current user by relative date, status, keyword, or orderId.",
                SCHEMA,
                true,
                true,
                Duration.ofSeconds(10),
                16_384,
                List.of()
        );
    }

    /**
     * 验证工具参数。
     *
     * @param ctx  工具使用上下文
     * @param use  工具使用请求
     * @return 验证结果
     */
    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            QueryArgs args = objectMapper.readValue(defaultJson(use.argsJson()), QueryArgs.class);
            if (args.status() != null) {
                OrderStatus.valueOf(args.status());
            }
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return ToolValidation.rejected(errorJson("invalid_args", e.getMessage()));
        }
    }

    /**
     * 执行订单查询操作。
     *
     * @param ctx              工具执行上下文
     * @param running          已启动的工具实例
     * @param normalizedArgsJson 标准化后的参数JSON
     * @param token            取消令牌
     * @return 工具执行结果
     */
    @Override
    protected ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws JsonProcessingException {
        ctx.sink().onToolProgress(new com.ai.agent.web.sse.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "querying", "正在查询订单", 40));
        QueryArgs args = objectMapper.readValue(normalizedArgsJson, QueryArgs.class);
        OrderQuery query = new OrderQuery(
                args.dateRange() == null ? "all" : args.dateRange(),
                args.status() == null ? null : OrderStatus.valueOf(args.status()),
                args.keyword(),
                args.orderId()
        );
        List<Order> orders = orderClient.queryOrders(ctx.userId(), query);
        String result = objectMapper.writeValueAsString(Map.of(
                "count", orders.size(),
                "orders", orders
        ));
        ctx.sink().onToolProgress(new com.ai.agent.web.sse.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "done", "订单查询完成", 100));
        return ToolTerminal.succeeded(running.call().toolCallId(), result);
    }

    private record QueryArgs(String dateRange, String status, String keyword, String orderId) {
    }
}
