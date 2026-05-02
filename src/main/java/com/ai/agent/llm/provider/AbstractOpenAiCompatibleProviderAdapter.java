package com.ai.agent.llm.provider;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.LlmUsage;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.llm.toolcall.ToolCallAssembler;
import com.ai.agent.tool.core.ToolSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * OpenAI兼容API提供商适配器基类。
 * <p>
 * 处理OpenAI兼容协议的通用流程，包括流式请求发送、错误处理、响应解析等。
 * 子类只需实现具体的API密钥、基础URL、超时时间等配置获取方法。
 * </p>
 */
public abstract class AbstractOpenAiCompatibleProviderAdapter implements LlmProviderAdapter {
    protected static final int MAX_CONNECT_RETRIES = 2;

    protected final ObjectMapper objectMapper;
    protected final HttpClient httpClient;
    protected final ProviderCompatibilityProfile compatibilityProfile;

    /**
     * 构造函数。
     *
     * @param objectMapper        JSON对象映射器
     * @param compatibilityProfile 提供者兼容性配置
     */
    protected AbstractOpenAiCompatibleProviderAdapter(
            ObjectMapper objectMapper,
            ProviderCompatibilityProfile compatibilityProfile
    ) {
        this.objectMapper = objectMapper;
        this.compatibilityProfile = compatibilityProfile;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 获取API密钥。
     *
     * @return API密钥字符串
     */
    protected abstract String getApiKey();

    /**
     * 获取API基础URL。
     *
     * @return 基础URL字符串
     */
    protected abstract String getBaseUrl();

    /**
     * 获取请求超时时间。
     *
     * @return 超时时间
     */
    protected abstract Duration getRequestTimeout();

    /**
     * 获取默认模型名称。
     *
     * @return 默认模型名称
     */
    protected abstract String getDefaultModel();

    /**
     * 获取提供商显示名称。
     *
     * @return 显示名称字符串
     */
    protected abstract String getProviderDisplayName();

    /**
     * 返回默认模型名称。
     *
     * @return 默认模型名称
     */
    @Override
    public String defaultModel() {
        return getDefaultModel();
    }

    /**
     * 执行流式聊天请求。
     * <p>
     * 发送HTTP请求到提供商API，处理流式响应并组装最终结果。
     * 支持连接重试机制。
     * </p>
     *
     * @param request  聊天请求
     * @param listener 流式监听器
     * @return 流式响应结果
     */
    @Override
    public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(getProviderDisplayName() + "_API_KEY is required");
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_CONNECT_RETRIES; attempt++) {
            try {
                request.beforeProviderCall();
                return doStream(request, listener, apiKey);
            } catch (ProviderCallException e) {
                if (e.type() != ProviderErrorType.RETRYABLE_PRE_STREAM) {
                    throw e;
                }
                last = e;
                sleepBackoff(attempt);
            }
        }
        throw last == null ? new IllegalStateException(getProviderDisplayName() + " request failed") : last;
    }

    /**
     * 执行实际的流式请求。
     *
     * @param request  聊天请求
     * @param listener 流式监听器
     * @param apiKey   API密钥
     * @return 流式响应结果
     */
    protected LlmStreamResult doStream(LlmChatRequest request, LlmStreamListener listener, String apiKey) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(getBaseUrl() + "/chat/completions"))
                .timeout(getRequestTimeout())
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toRequestBody(request)))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            throw new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, getProviderDisplayName() + " connection failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException(ProviderErrorType.NON_RETRYABLE, getProviderDisplayName() + " request interrupted", e);
        }

        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            closeErrorBody(response);
            throw new ProviderCallException(
                    ProviderErrorType.RETRYABLE_PRE_STREAM,
                    getProviderDisplayName() + " retryable status " + response.statusCode(),
                    response.statusCode()
            );
        }
        if (response.statusCode() >= 400) {
            closeErrorBody(response);
            throw new ProviderCallException(
                    ProviderErrorType.NON_RETRYABLE,
                    getProviderDisplayName() + " status " + response.statusCode(),
                    response.statusCode()
            );
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
            throw new ProviderCallException(
                    ProviderErrorType.STREAM_STARTED,
                    getProviderDisplayName() + " stream failed after headers were received",
                    e
            );
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

    /**
     * 将聊天请求转换为HTTP请求体JSON。
     *
     * @param request 聊天请求
     * @return JSON字符串
     */
    protected String toRequestBody(LlmChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", effectiveModel(request.model()));
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

    /**
     * 获取实际使用的模型名称。
     * <p>
     * 如果请求指定了模型则使用请求模型，否则使用默认模型。
     * </p>
     *
     * @param requestedModel 请求的模型名称
     * @return 实际使用的模型名称
     */
    protected String effectiveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        return getDefaultModel();
    }

    /**
     * 将LLM消息转换为提供商API格式。
     *
     * @param message LLM消息
     * @return 提供商API格式的消息对象
     */
    protected Map<String, Object> toProviderMessage(LlmMessage message) {
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

    /**
     * 解析JSON字符串为JsonNode。
     *
     * @param payload JSON字符串
     * @return JsonNode对象
     */
    protected JsonNode readJson(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid provider json", e);
        }
    }

    /**
     * 将对象序列化为JSON字符串。
     *
     * @param value 待序列化的对象
     * @return JSON字符串
     */
    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize json", e);
        }
    }

    /**
     * 从JsonNode读取使用量统计。
     *
     * @param usage 使用量JsonNode
     * @return LlmUsage实例
     */
    protected LlmUsage readUsage(JsonNode usage) {
        return new LlmUsage(
                usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt(),
                usage.path("total_tokens").isMissingNode() ? null : usage.path("total_tokens").asInt()
        );
    }

    /**
     * 映射提供商的结束原因到系统定义的FinishReason。
     *
     * @param value 提供商的结束原因字符串
     * @return FinishReason枚举值
     */
    protected FinishReason mapFinishReason(String value) {
        return switch (value) {
            case "tool_calls" -> FinishReason.TOOL_CALLS;
            case "length" -> FinishReason.LENGTH;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.STOP;
        };
    }

    /**
     * 获取JsonNode的文本值，如果节点为空则返回null。
     *
     * @param node JsonNode对象
     * @return 文本值或null
     */
    protected String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 隐藏payload中的API密钥信息。
     *
     * @param payload JSON字符串
     * @return 隐藏密钥后的字符串
     */
    protected String maskApiKey(String payload) {
        return payload.replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
    }

    /**
     * 关闭错误响应的响应体流。
     *
     * @param response HTTP响应对象
     */
    protected void closeErrorBody(HttpResponse<java.util.stream.Stream<String>> response) {
        try (java.util.stream.Stream<String> ignored = response.body()) {
            // Close provider error streams without storing potentially sensitive body content.
        }
    }

    /**
     * 执行退避等待。
     *
     * @param attempt 当前尝试次数
     */
    protected void sleepBackoff(int attempt) {
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
}