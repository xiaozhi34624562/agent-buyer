package com.ai.agent.llm.provider.deepseek;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.LlmUsage;
import com.ai.agent.llm.provider.ProviderCallException;
import com.ai.agent.llm.provider.ProviderErrorType;
import com.ai.agent.tool.core.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekProviderAdapterTest {
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
    void streamsContentUsageAndFinishReasonFromDeepSeekCompatibleSse() throws Exception {
        startServer(200, """
                data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                data: [DONE]

                """);
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(
                deepSeekProperties(),
                new DeepSeekCompatibilityProfile(objectMapper),
                objectMapper
        );

        LlmStreamResult result = adapter.streamChat(request(), ignored -> {
        });

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(result.usage()).isEqualTo(new LlmUsage(1, 2, 3));
        assertThat(capturedRequest.path()).isEqualTo("/chat/completions");
        assertThat(capturedRequest.authorization()).isEqualTo("Bearer test-deepseek-key");
        JsonNode body = objectMapper.readTree(capturedRequest.body());
        assertThat(body.path("model").asText()).isEqualTo("deepseek-reasoner");
        assertThat(body.path("stream").asBoolean()).isTrue();
    }

    @Test
    void mapsBadRequestToStableNonRetryableExceptionWithoutLeakingBody() throws Exception {
        startServer(400, "{\"error\":{\"message\":\"bad request secret prompt fragment\"}}");
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(
                deepSeekProperties(),
                new DeepSeekCompatibilityProfile(objectMapper),
                objectMapper
        );

        assertThatThrownBy(() -> adapter.streamChat(request(), ignored -> {
        }))
                .isInstanceOfSatisfying(ProviderCallException.class,
                        e -> {
                            assertThat(e.type()).isEqualTo(ProviderErrorType.NON_RETRYABLE);
                            assertThat(e.statusCode()).isEqualTo(400);
                        })
                .hasMessageContaining("DeepSeek status 400")
                .hasMessageNotContaining("secret prompt fragment");
        assertThat(requestCount).hasValue(1);
    }

    @Test
    void retriesServerErrorsBeforeFailingWithoutLeakingBody() throws Exception {
        startServer(500, "{\"error\":{\"message\":\"temporary secret prompt fragment\"}}");
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(
                deepSeekProperties(),
                new DeepSeekCompatibilityProfile(objectMapper),
                objectMapper
        );

        assertThatThrownBy(() -> adapter.streamChat(request(), ignored -> {
        }))
                .isInstanceOfSatisfying(ProviderCallException.class,
                        e -> {
                            assertThat(e.type()).isEqualTo(ProviderErrorType.RETRYABLE_PRE_STREAM);
                            assertThat(e.statusCode()).isEqualTo(500);
                        })
                .hasMessageContaining("DeepSeek retryable status 500")
                .hasMessageNotContaining("secret prompt fragment");
        assertThat(requestCount).hasValue(3);
    }

    @Test
    void classifiesMalformedStreamAsStreamStarted() throws Exception {
        startServer(200, "data: {not-json}\n\n");
        DeepSeekProviderAdapter adapter = new DeepSeekProviderAdapter(
                deepSeekProperties(),
                new DeepSeekCompatibilityProfile(objectMapper),
                objectMapper
        );

        assertThatThrownBy(() -> adapter.streamChat(request(), ignored -> {
        }))
                .isInstanceOfSatisfying(ProviderCallException.class,
                        e -> assertThat(e.type()).isEqualTo(ProviderErrorType.STREAM_STARTED))
                .hasMessageContaining("DeepSeek stream failed after headers were received");
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

    private CapturedRequest readRequest(HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    private AgentProperties deepSeekProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getLlm().getDeepseek().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getLlm().getDeepseek().setApiKey("test-deepseek-key");
        properties.getLlm().getDeepseek().setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private LlmChatRequest request() {
        return new LlmChatRequest(
                "run-1",
                "attempt-1",
                "deepseek-reasoner",
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
