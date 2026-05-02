package com.ai.agent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 敏感数据清洗工具类。
 *
 * <p>用于清洗JSON和文本中的敏感信息，包括API密钥、确认令牌、
 * 手机号、邮箱、身份证号、密码等，防止敏感数据泄露到日志或SSE输出。
 *
 * @author ai-agent
 */
@Component
public final class SensitivePayloadSanitizer {
    /**
     * 敏感信息替换占位符。
     */
    private static final String REDACTED = "[REDACTED]";

    /**
     * 精确匹配的敏感字段名称集合（小写无分隔符形式）。
     */
    private static final Set<String> SENSITIVE_EXACT_NAMES = Set.of(
            "confirmtoken",
            "authorization",
            "password",
            "idcard",
            "cardno"
    );

    /**
     * JSON载荷字段名称集合，这些字段的内容需要二次清洗。
     */
    private static final Set<String> JSON_PAYLOAD_FIELD_NAMES = Set.of(
            "argsjson",
            "resultjson",
            "errorjson",
            "payloadjson",
            "rawdiagnosticjson"
    );

    /**
     * API密钥匹配正则表达式。
     */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    /**
     * 确认令牌值匹配正则表达式。
     */
    private static final Pattern CONFIRM_TOKEN_VALUE_PATTERN = Pattern.compile("ct_[A-Za-z0-9_-]+");

    /**
     * 确认令牌文本匹配正则表达式。
     */
    private static final Pattern CONFIRM_TOKEN_TEXT_PATTERN = Pattern.compile(
            "(?i)confirm[_\\- ]?token\\s*[:=]?\\s*[^\\s,;}]+"
    );

    /**
     * 手机号匹配正则表达式。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b1[3-9]\\d{9}\\b");

    /**
     * 邮箱匹配正则表达式。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param objectMapper JSON对象映射器
     */
    public SensitivePayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 为SSE输出清洗数据。
     *
     * <p>将对象转换为JsonNode并进行敏感信息清洗，适用于SSE流式输出场景。
     *
     * @param value 待清洗的对象
     * @return 清洗后的JsonNode
     */
    public JsonNode sanitizeForSse(Object value) {
        if (value == null) {
            return objectMapper.nullNode();
        }
        if (value instanceof String text) {
            return TextNode.valueOf(sanitizeText(text));
        }
        return sanitizeNode(objectMapper.valueToTree(value));
    }

    /**
     * 将对象清洗后转换为JSON字符串。
     *
     * <p>对对象进行敏感信息清洗后序列化为JSON字符串。
     *
     * @param value 待清洗的对象
     * @return 清洗后的JSON字符串，序列化失败时返回错误标记JSON
     */
    public String sanitizeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof String text) {
                return sanitizeJsonString(text);
            }
            return objectMapper.writeValueAsString(sanitizeNode(objectMapper.valueToTree(value)));
        } catch (JsonProcessingException e) {
            return "{\"type\":\"serialization_failed\"}";
        }
    }

    /**
     * 清洗JSON字符串。
     *
     * <p>尝试解析字符串为JSON并进行清洗，解析失败时直接清洗文本。
     *
     * @param value 待清洗的JSON字符串
     * @return 清洗后的JSON字符串
     */
    public String sanitizeJsonString(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            return objectMapper.writeValueAsString(sanitizeNode(objectMapper.readTree(value)));
        } catch (JsonProcessingException e) {
            return sanitizeText(value);
        }
    }

    /**
     * 清洗文本中的敏感信息。
     *
     * <p>依次替换确认令牌、API密钥、手机号和邮箱等敏感信息。
     *
     * @param value 待清洗的文本
     * @return 清洗后的文本
     */
    public String sanitizeText(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = CONFIRM_TOKEN_TEXT_PATTERN.matcher(value).replaceAll(REDACTED);
        sanitized = CONFIRM_TOKEN_VALUE_PATTERN.matcher(sanitized).replaceAll("ct_***");
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll("sk-***");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("***");
        return EMAIL_PATTERN.matcher(sanitized).replaceAll("***");
    }

    /**
     * 从JSON中移除确认令牌字段。
     *
     * <p>删除JSON中的confirmtoken字段，同时清洗文本值中的敏感信息。
     *
     * @param json 待处理的JSON字符串
     * @return 处理后的JSON字符串
     */
    public String removeConfirmTokenFromJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return objectMapper.writeValueAsString(removeConfirmToken(root));
        } catch (JsonProcessingException e) {
            return sanitizeText(json);
        }
    }

    /**
     * 递归清洗JsonNode中的敏感信息。
     *
     * <p>处理ObjectNode、ArrayNode和文本节点，对敏感字段进行替换。
     *
     * @param node 待清洗的JsonNode
     * @return 清洗后的JsonNode
     */
    private JsonNode sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> names = new ArrayList<>();
            while (fields.hasNext()) {
                names.add(fields.next().getKey());
            }
            for (String name : names) {
                String normalized = normalize(name);
                JsonNode value = object.get(name);
                if (isSensitiveName(normalized)) {
                    object.set(name, TextNode.valueOf(REDACTED));
                } else if (isJsonPayloadField(normalized) && value != null && value.isTextual()) {
                    object.set(name, TextNode.valueOf(sanitizeJsonString(value.asText())));
                } else {
                    object.set(name, sanitizeNode(value));
                }
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, sanitizeNode(array.get(i)));
            }
            return array;
        }
        if (node != null && node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText()));
        }
        return node;
    }

    /**
     * 递归移除JsonNode中的确认令牌字段。
     *
     * <p>删除所有confirmtoken字段，同时清洗其他文本值中的敏感信息。
     *
     * @param node 待处理的JsonNode
     * @return 处理后的JsonNode
     */
    private JsonNode removeConfirmToken(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> names = new ArrayList<>();
            while (fields.hasNext()) {
                names.add(fields.next().getKey());
            }
            for (String name : names) {
                if ("confirmtoken".equals(normalize(name))) {
                    object.remove(name);
                } else {
                    object.set(name, removeConfirmToken(object.get(name)));
                }
            }
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, removeConfirmToken(array.get(i)));
            }
            return array;
        }
        if (node != null && node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText()));
        }
        return node;
    }

    /**
     * 判断字段名称是否为敏感字段。
     *
     * <p>检查字段名称是否在敏感字段列表中，或包含token、secret、apikey关键词。
     *
     * @param normalized 已标准化（小写无分隔符）的字段名称
     * @return 是否为敏感字段
     */
    private boolean isSensitiveName(String normalized) {
        return SENSITIVE_EXACT_NAMES.contains(normalized)
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("apikey");
    }

    /**
     * 判断字段名称是否为JSON载荷字段。
     *
     * <p>JSON载荷字段的内容需要二次清洗。
     *
     * @param normalized 已标准化的字段名称
     * @return 是否为JSON载荷字段
     */
    private boolean isJsonPayloadField(String normalized) {
        return JSON_PAYLOAD_FIELD_NAMES.contains(normalized);
    }

    /**
     * 标准化字段名称。
     *
     * <p>转换为小写并移除下划线和连字符，便于统一匹配。
     *
     * @param value 原始字段名称
     * @return 标准化后的字段名称
     */
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}