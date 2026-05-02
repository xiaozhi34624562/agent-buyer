package com.ai.agent.tool.builtin.order;

import com.ai.agent.business.order.CancelPreview;
import com.ai.agent.business.order.CancelResult;
import com.ai.agent.business.order.Order;
import com.ai.agent.business.order.OrderClient;
import com.ai.agent.tool.core.AbstractTool;
import com.ai.agent.tool.core.CancellationToken;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.ConfirmTokenStore;
import com.ai.agent.tool.security.PiiMasker;
import com.ai.agent.tool.security.ToolArgumentsHasher;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class CancelOrderTool extends AbstractTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "orderId": {
                  "type": "string",
                  "description": "Order ID to cancel. Query orders first if missing or ambiguous."
                },
                "confirmToken": {
                  "type": "string",
                  "description": "Server-issued token from a prior dry-run result. Required for the real cancellation."
                }
              },
              "required": ["orderId"],
              "additionalProperties": false
            }
            """;

    private final OrderClient orderClient;
    private final ConfirmTokenStore confirmTokenStore;
    private final ToolArgumentsHasher argumentsHasher;

    public CancelOrderTool(
            PiiMasker piiMasker,
            OrderClient orderClient,
            ConfirmTokenStore confirmTokenStore,
            ToolArgumentsHasher argumentsHasher,
            ObjectMapper objectMapper
    ) {
        super(piiMasker, objectMapper);
        this.orderClient = orderClient;
        this.confirmTokenStore = confirmTokenStore;
        this.argumentsHasher = argumentsHasher;
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "cancel_order",
                "Cancel an order. Without confirmToken it performs dry-run and returns PENDING_CONFIRM. With a valid confirmToken it executes cancellation.",
                SCHEMA,
                false,
                false,
                Duration.ofSeconds(15),
                16_384,
                List.of()
        );
    }

    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            CancelArgs args = objectMapper.readValue(defaultJson(use.argsJson()), CancelArgs.class);
            if (args.orderId() == null || args.orderId().isBlank()) {
                return ToolValidation.rejected(objectMapper.writeValueAsString(Map.of(
                        "type", "missing_order_id",
                        "message", "orderId is required",
                        "recoverable", true,
                        "nextActionRequired", "user_input",
                        "question", "请提供要取消的订单号。如果不确定订单号，可以先让我查询你的订单。"
                )));
            }
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return ToolValidation.rejected(errorJson("invalid_args", e.getMessage()));
        }
    }

    @Override
    protected ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws JsonProcessingException {
        CancelArgs args = objectMapper.readValue(normalizedArgsJson, CancelArgs.class);
        ctx.sink().onToolProgress(new com.ai.agent.web.sse.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "checking", "正在校验订单状态", 30));
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running);
        }
        if (args.confirmToken() == null || args.confirmToken().isBlank()) {
            CancelPreview preview = orderClient.previewCancelOrder(ctx.userId(), args.orderId());
            if (!preview.cancellable()) {
                return ToolTerminal.failed(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                        "type", "not_cancellable",
                        "message", preview.summary(),
                        "reason", preview.reason()
                )));
            }
            String argsHash = argumentsHasher.hash(normalizedArgsJson);
            String confirmToken = confirmTokenStore.create(ctx.runId(), ctx.userId(), schema().name(), argsHash);
            String result = objectMapper.writeValueAsString(Map.of(
                    "actionStatus", "PENDING_CONFIRM",
                    "orderId", args.orderId(),
                    "summary", preview.summary(),
                    "confirmToken", confirmToken
            ));
            ctx.sink().onToolProgress(new com.ai.agent.web.sse.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "pending_confirmation", "等待用户确认取消订单", 100));
            return ToolTerminal.succeeded(running.call().toolCallId(), result);
        }

        String argsHash = argumentsHasher.hash(normalizedArgsJson);
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running);
        }
        boolean accepted = confirmTokenStore.consume(ctx.runId(), ctx.userId(), args.confirmToken(), schema().name(), argsHash);
        if (!accepted) {
            return ToolTerminal.failed(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                    "type", "invalid_confirm_token",
                "message", "confirmToken is missing, expired, or does not match the dry-run arguments"
            )));
        }
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running);
        }
        ctx.sink().onToolProgress(new com.ai.agent.web.sse.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "cancelling", "正在取消订单", 70));
        CancelResult result = orderClient.cancelOrder(ctx.userId(), args.orderId());
        ctx.sink().onToolProgress(new com.ai.agent.web.sse.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "done", "订单取消完成", 100));
        return ToolTerminal.succeeded(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                "actionStatus", "COMPLETED",
                "orderId", result.orderId(),
                "status", result.status().name(),
                "summary", result.summary()
        )));
    }

    private record CancelArgs(String orderId, String confirmToken) {
    }
}
