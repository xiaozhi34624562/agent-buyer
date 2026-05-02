package com.ai.agent.tool.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PII（个人敏感信息）脱敏器，用于掩码JSON中的敏感字段。
 *
 * <p>对指定字段和预定义的敏感字段名称进行脱敏处理，防止敏感信息泄露到日志或LLM交互中。
 */
@Component
public final class PiiMasker {
    /** 默认敏感字段名称集合 */
    private static final Set<String> DEFAULT_SENSITIVE_NAMES = Set.of(
            "phone", "mobile", "email", "idcard", "cardno", "token", "apikey"
    );

    private final ObjectMapper objectMapper;

    public PiiMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对JSON字符串进行敏感字段脱敏处理。
     *
     * @param json 待脱敏的JSON字符串
     * @param sensitiveFields 额外的敏感字段名称列表
     * @return 脱敏后的JSON字符串，若解析失败则进行简单正则替换
     */
    public String maskJson(String json, List<String> sensitiveFields) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            Set<String> sensitive = new HashSet<>(DEFAULT_SENSITIVE_NAMES);
            for (String field : sensitiveFields) {
                sensitive.add(normalize(field));
            }
            JsonNode masked = maskNode(root.deepCopy(), sensitive);
            return objectMapper.writeValueAsString(masked);
        } catch (JsonProcessingException e) {
            return json.replaceAll("sk-[A-Za-z0-9_-]{12,}", "sk-***");
        }
    }

    /**
     * 递归脱敏JSON节点。
     *
     * @param node JSON节点
     * @param sensitive 敏感字段名称集合
     * @return 脱敏后的JSON节点
     */
    private JsonNode maskNode(JsonNode node, Set<String> sensitive) {
        if (node instanceof ObjectNode object) {
            object.fields().forEachRemaining(entry -> {
                if (sensitive.contains(normalize(entry.getKey())) && entry.getValue().isValueNode()) {
                    object.put(entry.getKey(), maskValue(entry.getValue().asText()));
                } else {
                    maskNode(entry.getValue(), sensitive);
                }
            });
        } else if (node instanceof ArrayNode array) {
            array.forEach(item -> maskNode(item, sensitive));
        }
        return node;
    }

    /**
     * 标准化字段名称，用于敏感字段匹配。
     *
     * @param value 原始字段名称
     * @return 标准化后的名称（小写、去除下划线和连字符）
     */
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * 对敏感值进行掩码处理。
     *
     * @param value 原始敏感值
     * @return 脱敏后的值，保留最后4个字符
     */
    private String maskValue(String value) {
        if (value == null || value.length() <= 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }
}
