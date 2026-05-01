package com.ai.agent.tool.builtin.order;

import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.FinalEvent;
import com.ai.agent.web.sse.TextDeltaEvent;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.ai.agent.web.sse.ToolResultEvent;
import com.ai.agent.web.sse.ToolUseEvent;
import com.ai.agent.business.order.OrderStatus;
import com.ai.agent.persistence.entity.BusinessOrderEntity;
import com.ai.agent.persistence.mapper.BusinessOrderMapper;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.ToolStatus;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CancelOrderToolIntegrationTest {
    @Autowired
    CancelOrderTool tool;

    @Autowired
    BusinessOrderMapper orderMapper;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void missingOrderIdValidationIsRecoverableUserInputRequest() throws Exception {
        ToolValidation validation = tool.validate(
                new ToolUseContext("run-1", "demo-user"),
                new ToolUse("call-1", "cancel_order", "{}")
        );

        assertThat(validation.accepted()).isFalse();
        JsonNode error = objectMapper.readTree(validation.errorJson());
        assertThat(error.path("type").asText()).isEqualTo("missing_order_id");
        assertThat(error.path("recoverable").asBoolean()).isTrue();
        assertThat(error.path("nextActionRequired").asText()).isEqualTo("user_input");
        assertThat(error.path("question").asText()).contains("订单号");
    }

    @Test
    void confirmTokenRejectsChangedArgumentsBeforeAllowingOriginalArguments() throws Exception {
        String runId = Ids.newId("test_run");
        String orderId = Ids.newId("O_TEST");
        try {
            insertPaidOrder(orderId);

            ToolTerminal dryRun = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, null),
                    () -> false
            );
            JsonNode dryRunResult = objectMapper.readTree(dryRun.resultJson());
            String confirmToken = dryRunResult.path("confirmToken").asText();

            ToolTerminal changedArgs = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, "O-DOES-NOT-MATCH", confirmToken),
                    () -> false
            );

            assertThat(changedArgs.status()).isEqualTo(ToolStatus.FAILED);
            assertThat(changedArgs.errorJson()).contains("invalid_confirm_token");

            ToolTerminal confirmed = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, confirmToken),
                    () -> false
            );

            assertThat(confirmed.status()).isEqualTo(ToolStatus.SUCCEEDED);
            assertThat(confirmed.resultJson()).contains("COMPLETED");
        } finally {
            redisTemplate.delete("agent:{run:" + runId + "}:confirm-tokens");
        }
    }

    @Test
    void repeatedDryRunInvalidatesPreviousConfirmTokenForSameAction() throws Exception {
        String runId = Ids.newId("test_run");
        String orderId = Ids.newId("O_TEST");
        try {
            insertPaidOrder(orderId);

            ToolTerminal firstDryRun = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, null),
                    () -> false
            );
            String firstToken = objectMapper.readTree(firstDryRun.resultJson()).path("confirmToken").asText();

            ToolTerminal secondDryRun = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, null),
                    () -> false
            );
            String secondToken = objectMapper.readTree(secondDryRun.resultJson()).path("confirmToken").asText();

            assertThat(secondToken).isNotEqualTo(firstToken);

            ToolTerminal oldTokenResult = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, firstToken),
                    () -> false
            );
            assertThat(oldTokenResult.status()).isEqualTo(ToolStatus.FAILED);
            assertThat(oldTokenResult.errorJson()).contains("invalid_confirm_token");

            ToolTerminal newTokenResult = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, secondToken),
                    () -> false
            );
            assertThat(newTokenResult.status()).isEqualTo(ToolStatus.SUCCEEDED);
        } finally {
            redisTemplate.delete("agent:{run:" + runId + "}:confirm-tokens");
        }
    }

    @Test
    void cancellationTokenStopsConfirmedCancelBeforeBusinessSideEffect() throws Exception {
        String runId = Ids.newId("test_run");
        String orderId = Ids.newId("O_TEST");
        try {
            insertPaidOrder(orderId);

            ToolTerminal dryRun = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, null),
                    () -> false
            );
            String confirmToken = objectMapper.readTree(dryRun.resultJson()).path("confirmToken").asText();

            ToolTerminal cancelled = tool.run(
                    new ToolExecutionContext(runId, "demo-user", NoopSink.INSTANCE),
                    started(runId, orderId, confirmToken),
                    () -> true
            );

            assertThat(cancelled.status()).isEqualTo(ToolStatus.CANCELLED);
            assertThat(cancelled.cancelReason()).isEqualTo(CancelReason.RUN_ABORTED);
            assertThat(cancelled.synthetic()).isTrue();
            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.PAID.name());
        } finally {
            redisTemplate.delete("agent:{run:" + runId + "}:confirm-tokens");
        }
    }

    private StartedTool started(String runId, String orderId, String confirmToken) throws Exception {
        String argsJson = confirmToken == null
                ? objectMapper.writeValueAsString(java.util.Map.of("orderId", orderId))
                : objectMapper.writeValueAsString(java.util.Map.of("orderId", orderId, "confirmToken", confirmToken));
        ToolCall call = new ToolCall(
                runId,
                Ids.newId("tc"),
                1,
                Ids.newId("call"),
                "cancel_order",
                "cancel_order",
                tool.validate(new ToolUseContext(runId, "demo-user"), new ToolUse(Ids.newId("call"), "cancel_order", argsJson)).normalizedArgsJson(),
                false,
                false,
                false,
                null
        );
        return new StartedTool(call, 1, "lease", System.currentTimeMillis() + 90_000, "test");
    }

    private void insertPaidOrder(String orderId) {
        BusinessOrderEntity entity = new BusinessOrderEntity();
        entity.setOrderId(orderId);
        entity.setUserId("demo-user");
        entity.setStatus(OrderStatus.PAID.name());
        entity.setCreatedAt(LocalDateTime.now().minusDays(1));
        entity.setAmount(new BigDecimal("12.34"));
        entity.setItemName("Integration Test Item");
        orderMapper.insert(entity);
    }

    private enum NoopSink implements AgentEventSink {
        INSTANCE;

        @Override
        public void onTextDelta(TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
        }

        @Override
        public void onFinal(FinalEvent event) {
        }

        @Override
        public void onError(ErrorEvent event) {
        }
    }
}
