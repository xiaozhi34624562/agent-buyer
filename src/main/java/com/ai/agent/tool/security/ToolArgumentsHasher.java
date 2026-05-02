package com.ai.agent.tool.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * 工具参数哈希器，用于生成工具调用参数的一致性哈希值。
 *
 * <p>通过规范化JSON结构并使用SHA-256算法生成哈希，用于确认令牌匹配验证，
 * 确保用户确认的操作与实际执行的操作参数一致。
 */
@Component
public final class ToolArgumentsHasher {
    private final ObjectMapper mapper;

    public ToolArgumentsHasher() {
        this.mapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    /**
     * 对工具参数JSON计算SHA-256哈希值。
     *
     * <p>哈希计算前会移除运行时字段（如confirmToken、dryRun），并对JSON进行规范化排序。
     *
     * @param argsJson 工具参数JSON字符串
     * @return SHA-256哈希的十六进制字符串
     */
    public String hash(String argsJson) {
        try {
            JsonNode root = mapper.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            JsonNode sanitized = canonicalize(removeRuntimeFields(root));
            byte[] canonical = mapper.writeValueAsBytes(sanitized);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("tool args must be valid JSON", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 移除JSON中的运行时字段。
     *
     * @param root 原始JSON节点
     * @return 移除confirmToken和dryRun字段后的JSON节点
     */
    private JsonNode removeRuntimeFields(JsonNode root) {
        JsonNode copy = root.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove("confirmToken");
            object.remove("dryRun");
        }
        return copy;
    }

    /**
     * 对JSON节点进行规范化处理，确保字段按字母顺序排列。
     *
     * @param node 原始JSON节点
     * @return 规范化后的JSON节点
     */
    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> array.add(canonicalize(item)));
            return array;
        }
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
        fields.forEach((key, value) -> object.set(key, canonicalize(value)));
        return object;
    }
}
