package com.ai.agent.tool;

import com.ai.agent.business.CancelPreview;
import com.ai.agent.business.CancelResult;
import com.ai.agent.business.OrderClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    public CancelOrderTool(
            PiiMasker piiMasker,
            OrderClient orderClient,
            ConfirmTokenStore confirmTokenStore,
            ToolArgumentsHasher argumentsHasher,
            ObjectMapper objectMapper
    ) {
        super(piiMasker);
        this.orderClient = orderClient;
        this.confirmTokenStore = confirmTokenStore;
        this.argumentsHasher = argumentsHasher;
        this.objectMapper = objectMapper;
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
                return ToolValidation.rejected(error("missing_order_id", "orderId is required"));
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
        CancelArgs args = objectMapper.readValue(normalizedArgsJson, CancelArgs.class);
        ctx.sink().onToolProgress(new com.ai.agent.api.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "checking", "正在校验订单状态", 30));
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
            String confirmToken = confirmTokenStore.create(ctx.runId(), schema().name(), argsHash);
            String result = objectMapper.writeValueAsString(Map.of(
                    "actionStatus", "PENDING_CONFIRM",
                    "orderId", args.orderId(),
                    "summary", preview.summary(),
                    "confirmToken", confirmToken
            ));
            ctx.sink().onToolProgress(new com.ai.agent.api.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "pending_confirmation", "等待用户确认取消订单", 100));
            return ToolTerminal.succeeded(running.call().toolCallId(), result);
        }

        String argsHash = argumentsHasher.hash(normalizedArgsJson);
        boolean accepted = confirmTokenStore.consume(ctx.runId(), args.confirmToken(), schema().name(), argsHash);
        if (!accepted) {
            return ToolTerminal.failed(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                    "type", "invalid_confirm_token",
                    "message", "confirmToken is missing, expired, or does not match the dry-run arguments"
            )));
        }
        ctx.sink().onToolProgress(new com.ai.agent.api.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "cancelling", "正在取消订单", 70));
        CancelResult result = orderClient.cancelOrder(ctx.userId(), args.orderId());
        ctx.sink().onToolProgress(new com.ai.agent.api.ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "done", "订单取消完成", 100));
        return ToolTerminal.succeeded(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                "actionStatus", "COMPLETED",
                "orderId", result.orderId(),
                "status", result.status().name(),
                "summary", result.summary()
        )));
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

    private record CancelArgs(String orderId, String confirmToken) {
    }
}
