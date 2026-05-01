package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.tool.ToolSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class DeepSeekProviderAdapter implements LlmProviderAdapter {
    private static final int MAX_CONNECT_RETRIES = 2;
    private static final String PROVIDER_NAME = "deepseek";

    private final AgentProperties properties;
    private final DeepSeekCompatibilityProfile compatibilityProfile;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekProviderAdapter(
            AgentProperties properties,
            DeepSeekCompatibilityProfile compatibilityProfile,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.compatibilityProfile = compatibilityProfile;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
        String apiKey = properties.getLlm().getDeepseek().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required");
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_CONNECT_RETRIES; attempt++) {
            try {
                return doStream(request, listener, apiKey);
            } catch (RetryableConnectException e) {
                last = e;
                sleepBackoff(attempt);
            }
        }
        throw last == null ? new IllegalStateException("DeepSeek request failed") : last;
    }

    private LlmStreamResult doStream(LlmChatRequest request, LlmStreamListener listener, String apiKey) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.getLlm().getDeepseek().getBaseUrl() + "/chat/completions"))
                .timeout(properties.getLlm().getDeepseek().getRequestTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toRequestBody(request)))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new RetryableConnectException("DeepSeek connection failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek request interrupted", e);
        }

        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            String body = String.join("\n", response.body().toList());
            throw new RetryableConnectException("DeepSeek retryable status " + response.statusCode() + ": " + body);
        }
        if (response.statusCode() >= 400) {
            String body = String.join("\n", response.body().toList());
            throw new IllegalStateException("DeepSeek status " + response.statusCode() + ": " + body);
        }

        StringBuilder content = new StringBuilder();
        ToolCallAssembler toolCallAssembler = new ToolCallAssembler();
        FinishReason[] finishReason = new FinishReason[1];
        LlmUsage[] usage = new LlmUsage[1];
        List<String> diagnostics = new ArrayList<>();

        try (java.util.stream.Stream<String> lines = response.body()) {
            lines.forEachOrdered(line -> {
                if (!line.startsWith("data:")) {
                    return;
                }
                String payload = line.substring("data:".length()).trim();
                if ("[DONE]".equals(payload)) {
                    return;
                }
                diagnostics.add(maskApiKey(payload));
                JsonNode root = readJson(payload);
                JsonNode usageNode = root.get("usage");
                if (usageNode != null && !usageNode.isNull()) {
                    usage[0] = readUsage(usageNode);
                }
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    return;
                }
                JsonNode choice = choices.get(0);
                String finish = textOrNull(choice.get("finish_reason"));
                if (finish != null) {
                    finishReason[0] = mapFinishReason(finish);
                }
                JsonNode delta = choice.get("delta");
                if (delta == null || delta.isNull()) {
                    return;
                }
                String text = textOrNull(delta.get("content"));
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                    listener.onTextDelta(text);
                }
                JsonNode toolCalls = delta.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode toolCall : toolCalls) {
                        int index = toolCall.path("index").asInt(0);
                        String id = textOrNull(toolCall.get("id"));
                        JsonNode function = toolCall.get("function");
                        String name = function == null ? null : textOrNull(function.get("name"));
                        String args = function == null ? null : textOrNull(function.get("arguments"));
                        toolCallAssembler.append(index, id, name, args);
                    }
                }
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("DeepSeek stream failed after headers were received", e);
        }

        List<ToolCallMessage> toolCalls = toolCallAssembler.complete();
        if (!toolCalls.isEmpty()) {
            finishReason[0] = FinishReason.TOOL_CALLS;
        }
        return new LlmStreamResult(
                content.toString(),
                toolCalls,
                finishReason[0] == null ? FinishReason.STOP : finishReason[0],
                usage[0],
                toJson(Map.of("chunks", diagnostics))
        );
    }

    private String toRequestBody(LlmChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        body.put("messages", request.messages().stream().map(this::toProviderMessage).toList());
        List<ToolSchema> tools = request.tools() == null ? List.of() : request.tools();
        if (!tools.isEmpty()) {
            body.put("tools", compatibilityProfile.toProviderTools(tools));
            body.put("tool_choice", "auto");
        }
        return toJson(body);
    }

    private Map<String, Object> toProviderMessage(LlmMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", message.role().name().toLowerCase());
        if (message.content() != null) {
            out.put("content", message.content());
        }
        if (message.toolUseId() != null) {
            out.put("tool_call_id", message.toolUseId());
        }
        if (!message.toolCalls().isEmpty()) {
            out.put("tool_calls", message.toolCalls().stream()
                    .map(call -> Map.<String, Object>of(
                            "id", call.toolUseId(),
                            "type", "function",
                            "function", Map.of(
                                    "name", call.name(),
                                    "arguments", call.argsJson()
                            )
                    ))
                    .toList());
        }
        return out;
    }

    private JsonNode readJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid provider json", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize json", e);
        }
    }

    private LlmUsage readUsage(JsonNode usage) {
        return new LlmUsage(
                usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt(),
                usage.path("total_tokens").isMissingNode() ? null : usage.path("total_tokens").asInt()
        );
    }

    private FinishReason mapFinishReason(String value) {
        return switch (value) {
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.STOP;
        };
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private String maskApiKey(String payload) {
        return payload.replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
    }

    private void sleepBackoff(int attempt) {
        if (attempt >= MAX_CONNECT_RETRIES) {
            return;
        }
        long millis = attempt == 0 ? 200 : 800;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry interrupted", e);
        }
    }

    private static final class RetryableConnectException extends RuntimeException {
        private RetryableConnectException(String message) {
            super(message);
        }

        private RetryableConnectException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
