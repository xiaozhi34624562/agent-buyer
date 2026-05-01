package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.tool.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenProviderAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private volatile CapturedRequest capturedRequest;
    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsContentToolCallsUsageAndFinishReasonFromQwenCompatibleSse() throws Exception {
        startServer(200, """
                data: {"choices":[{"delta":{"content":"正在查询"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_qwen_1","type":"function","function":{"name":"query_","arguments":"{\\"order"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"order","arguments":"Id\\":\\"A"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"100\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12}}

                data: [DONE]

                """);
        AgentProperties properties = qwenProperties();
        QwenProviderAdapter adapter = new QwenProviderAdapter(
                properties,
                new QwenCompatibilityProfile(objectMapper),
                objectMapper
        );
        List<String> textDeltas = new ArrayList<>();

        LlmStreamResult result = adapter.streamChat(
                request(),
                textDeltas::add
        );

        assertThat(textDeltas).containsExactly("正在查询");
        assertThat(result.content()).isEqualTo("正在查询");
        assertThat(result.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(result.usage()).isEqualTo(new LlmUsage(7, 5, 12));
        assertThat(result.toolCalls()).containsExactly(new ToolCallMessage(
                "call_qwen_1",
                "query_order",
                "{\"orderId\":\"A100\"}"
        ));
        assertThat(capturedRequest.path()).isEqualTo("/chat/completions");
        assertThat(capturedRequest.authorization()).isEqualTo("Bearer test-qwen-key");
        JsonNode body = objectMapper.readTree(capturedRequest.body());
        assertThat(body.path("model").asText()).isEqualTo("qwen-plus");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("tools").get(0).path("function").path("name").asText()).isEqualTo("query_order");
    }

    @Test
    void mapsBadRequestToStableNonRetryableException() throws Exception {
        startServer(400, "{\"error\":{\"message\":\"bad request secret prompt fragment\"}}");
        QwenProviderAdapter adapter = new QwenProviderAdapter(
                qwenProperties(),
                new QwenCompatibilityProfile(objectMapper),
                objectMapper
        );

        assertThatThrownBy(() -> adapter.streamChat(request(), ignored -> {
        }))
                .isInstanceOfSatisfying(ProviderCallException.class,
                        e -> {
                            assertThat(e.type()).isEqualTo(ProviderErrorType.NON_RETRYABLE);
                            assertThat(e.statusCode()).isEqualTo(400);
                        })
                .hasMessageContaining("Qwen status 400")
                .hasMessageNotContaining("secret prompt fragment");
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void retriesServerErrorsBeforeFailing() throws Exception {
        startServer(500, "{\"error\":{\"message\":\"temporary secret prompt fragment\"}}");
        QwenProviderAdapter adapter = new QwenProviderAdapter(
                qwenProperties(),
                new QwenCompatibilityProfile(objectMapper),
                objectMapper
        );

        assertThatThrownBy(() -> adapter.streamChat(request(), ignored -> {
        }))
                .isInstanceOfSatisfying(ProviderCallException.class,
                        e -> {
                            assertThat(e.type()).isEqualTo(ProviderErrorType.RETRYABLE_PRE_STREAM);
                            assertThat(e.statusCode()).isEqualTo(500);
                        })
                .hasMessageContaining("Qwen retryable status 500")
                .hasMessageNotContaining("secret prompt fragment");
        assertThat(requestCount).hasValue(3);
    }

    @Test
    void failsBeforeHttpCallWhenApiKeyIsMissing() throws Exception {
        startServer(200, "data: [DONE]\n\n");
        AgentProperties properties = qwenProperties();
        properties.getLlm().getQwen().setApiKey(" ");
        QwenProviderAdapter adapter = new QwenProviderAdapter(
                properties,
                new QwenCompatibilityProfile(objectMapper),
                objectMapper
        );

        assertThatThrownBy(() -> adapter.streamChat(request(), ignored -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("QWEN_API_KEY is required");
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void mapsLengthAndContentFilterFinishReasons() throws Exception {
        assertFinishReason("length", FinishReason.LENGTH);
        assertFinishReason("content_filter", FinishReason.CONTENT_FILTER);
    }

    @Test
    void usesConfiguredDefaultModelWhenRequestModelIsBlank() throws Exception {
        startServer(200, """
                data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                data: [DONE]

                """);
        AgentProperties properties = qwenProperties();
        properties.getLlm().getQwen().setDefaultModel("qwen-custom");
        QwenProviderAdapter adapter = new QwenProviderAdapter(
                properties,
                new QwenCompatibilityProfile(objectMapper),
                objectMapper
        );

        adapter.streamChat(requestWithModel(" "), ignored -> {
        });

        JsonNode body = objectMapper.readTree(capturedRequest.body());
        assertThat(body.path("model").asText()).isEqualTo("qwen-custom");
    }

    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            capturedRequest = readRequest(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    private void assertFinishReason(String providerFinishReason, FinishReason expected) throws Exception {
        startServer(200, """
                data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"%s"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                data: [DONE]

                """.formatted(providerFinishReason));
        try {
            QwenProviderAdapter adapter = new QwenProviderAdapter(
                    qwenProperties(),
                    new QwenCompatibilityProfile(objectMapper),
                    objectMapper
            );

            LlmStreamResult result = adapter.streamChat(request(), ignored -> {
            });

            assertThat(result.finishReason()).isEqualTo(expected);
        } finally {
            stopServer();
            server = null;
        }
    }

    private CapturedRequest readRequest(HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    private AgentProperties qwenProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getLlm().getQwen().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getLlm().getQwen().setApiKey("test-qwen-key");
        properties.getLlm().getQwen().setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private LlmChatRequest request() {
        return requestWithModel("qwen-plus");
    }

    private LlmChatRequest requestWithModel(String model) {
        return new LlmChatRequest(
                "run-1",
                "attempt-1",
                model,
                0.2,
                256,
                List.of(LlmMessage.system("msg-1", "You are helpful")),
                List.of(new ToolSchema(
                        "query_order",
                        "Query order status",
                        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}",
                        true,
                        true,
                        Duration.ofSeconds(5),
                        1024,
                        List.of()
                ))
        );
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }
}
