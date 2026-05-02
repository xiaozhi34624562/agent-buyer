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

@Component
public final class SensitivePayloadSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_EXACT_NAMES = Set.of(
            "confirmtoken",
            "authorization",
            "password",
            "idcard",
            "cardno"
    );
    private static final Set<String> JSON_PAYLOAD_FIELD_NAMES = Set.of(
            "argsjson",
            "resultjson",
            "errorjson",
            "payloadjson",
            "rawdiagnosticjson"
    );
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern CONFIRM_TOKEN_VALUE_PATTERN = Pattern.compile("ct_[A-Za-z0-9_-]+");
    private static final Pattern CONFIRM_TOKEN_TEXT_PATTERN = Pattern.compile(
            "(?i)confirm[_\\- ]?token\\s*[:=]?\\s*[^\\s,;}]+"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    private final ObjectMapper objectMapper;

    public SensitivePayloadSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode sanitizeForSse(Object value) {
        if (value == null) {
            return objectMapper.nullNode();
        }
        if (value instanceof String text) {
            return TextNode.valueOf(sanitizeText(text));
        }
        return sanitizeNode(objectMapper.valueToTree(value));
    }

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

    private boolean isSensitiveName(String normalized) {
        return SENSITIVE_EXACT_NAMES.contains(normalized)
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("apikey");
    }

    private boolean isJsonPayloadField(String normalized) {
        return JSON_PAYLOAD_FIELD_NAMES.contains(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
