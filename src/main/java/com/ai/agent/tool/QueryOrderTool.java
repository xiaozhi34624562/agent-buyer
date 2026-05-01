package com.ai.agent.tool;

import com.ai.agent.business.Order;
import com.ai.agent.business.OrderClient;
import com.ai.agent.business.OrderQuery;
import com.ai.agent.business.OrderStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    public QueryOrderTool(PiiMasker piiMasker, OrderClient orderClient, ObjectMapper objectMapper) {
        super(piiMasker);
        this.orderClient = orderClient;
        this.objectMapper = objectMapper;
    }

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

    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            QueryArgs args = objectMapper.readValue(defaultJson(use.argsJson()), QueryArgs.class);
            if (args.status() != null) {
                OrderStatus.valueOf(args.status());
            }
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return ToolValidation.rejected(error("invalid_args", e.getMessage()));
        }
    }

    @Override
    protected ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws JsonProcessingException {
        ctx.sink().onToolProgress(new com.ai.agent.api.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "querying", "正在查询订单", 40));
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
        ctx.sink().onToolProgress(new com.ai.agent.api.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "done", "订单查询完成", 100));
        return ToolTerminal.succeeded(running.call().toolCallId(), result);
    }

    private String defaultJson(String argsJson) {
        return argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
    }

    private String error(String type, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("type", type, "message", message == null ? "" : message));
        } catch (JsonProcessingException e) {
            return "{\"type\":\"invalid_args\"}";
        }
    }

    private record QueryArgs(String dateRange, String status, String keyword, String orderId) {
    }
}
