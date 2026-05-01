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

@Component
public final class ToolArgumentsHasher {
    private final ObjectMapper mapper;

    public ToolArgumentsHasher() {
        this.mapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

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

    private JsonNode removeRuntimeFields(JsonNode root) {
        JsonNode copy = root.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove("confirmToken");
            object.remove("dryRun");
        }
        return copy;
    }

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
