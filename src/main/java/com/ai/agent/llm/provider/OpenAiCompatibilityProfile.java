package com.ai.agent.llm.provider;

import com.ai.agent.tool.core.ToolSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OpenAI-compatible tool schema format.
 * Used by providers that follow OpenAI's function calling convention.
 */
@Component
public final class OpenAiCompatibilityProfile implements ProviderCompatibilityProfile {
    private final ObjectMapper objectMapper;

    public OpenAiCompatibilityProfile(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> toProviderTools(List<ToolSchema> schemas) {
        return schemas.stream()
                .map(schema -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", schema.name(),
                                "description", schema.description(),
                                "parameters", parseSchema(schema.parametersJsonSchema())
                        )
                ))
                .toList();
    }

    private Object parseSchema(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid tool schema json", e);
        }
    }
}