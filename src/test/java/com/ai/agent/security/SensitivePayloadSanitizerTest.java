package com.ai.agent.security;

import com.ai.agent.tool.model.ToolStatus;
import com.ai.agent.web.sse.ToolResultEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitivePayloadSanitizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SensitivePayloadSanitizer sanitizer = new SensitivePayloadSanitizer(objectMapper);

    @Test
    void sanitizesNestedConfirmTokenInSsePayload() throws Exception {
        ToolResultEvent event = new ToolResultEvent(
                "run-1",
                "call-1",
                ToolStatus.SUCCEEDED,
                "{\"summary\":\"ok\",\"confirmToken\":\"ct_secret_token\"}",
                null
        );

        String json = objectMapper.writeValueAsString(sanitizer.sanitizeForSse(event));

        assertThat(json)
                .doesNotContain("ct_secret_token")
                .contains("[REDACTED]");
    }

    @Test
    void removesConfirmTokenFromExecutionArgsBeforeTrajectoryWrite() {
        String sanitized = sanitizer.removeConfirmTokenFromJson("""
                {"orderId":"O-1001","confirmToken":"ct_secret_token","nested":{"confirmToken":"ct_nested"}}
                """);

        assertThat(sanitized)
                .doesNotContain("confirmToken")
                .doesNotContain("ct_secret_token")
                .doesNotContain("ct_nested")
                .contains("O-1001");
    }
}
