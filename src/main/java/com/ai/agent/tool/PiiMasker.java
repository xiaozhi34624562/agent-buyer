package com.ai.agent.tool;

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

@Component
public final class PiiMasker {
    private static final Set<String> DEFAULT_SENSITIVE_NAMES = Set.of(
            "phone", "mobile", "email", "idcard", "cardno", "token", "apikey"
    );

    private final ObjectMapper objectMapper;

    public PiiMasker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private String maskValue(String value) {
        if (value == null || value.length() <= 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }
}
