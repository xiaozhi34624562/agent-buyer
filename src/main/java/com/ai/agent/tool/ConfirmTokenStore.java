package com.ai.agent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public final class ConfirmTokenStore {
    private final AgentProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ConfirmTokenStore(AgentProperties properties, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public String create(String runId, String userId, String toolName, String argsHash) {
        String token = Ids.newId("ct");
        ConfirmToken value = new ConfirmToken(userId, toolName, argsHash, Instant.now().plus(properties.getConfirmationTtl()).toEpochMilli());
        String key = key(runId);
        removeMatchingActionTokens(key, userId, toolName, argsHash);
        redisTemplate.opsForHash().put(key, token, toJson(value));
        redisTemplate.expire(key, properties.getConfirmationTtl());
        return token;
    }

    public boolean consume(String runId, String userId, String token, String toolName, String argsHash) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = key(runId);
        Object raw = redisTemplate.opsForHash().get(key, token);
        if (raw == null) {
            return false;
        }
        ConfirmToken stored = fromJson(raw.toString());
        if (stored.expiresAtMs() < Instant.now().toEpochMilli()) {
            redisTemplate.opsForHash().delete(key, token);
            return false;
        }
        if (!stored.userId().equals(userId) || !stored.toolName().equals(toolName) || !stored.argsHash().equals(argsHash)) {
            return false;
        }
        redisTemplate.opsForHash().delete(key, token);
        return true;
    }

    public void clearRun(String runId) {
        redisTemplate.delete(key(runId));
    }

    private void removeMatchingActionTokens(String key, String userId, String toolName, String argsHash) {
        redisTemplate.opsForHash().entries(key).forEach((field, raw) -> {
            ConfirmToken stored = fromJson(raw.toString());
            if (stored.userId().equals(userId) && stored.toolName().equals(toolName) && stored.argsHash().equals(argsHash)) {
                redisTemplate.opsForHash().delete(key, field);
            }
        });
    }

    private String key(String runId) {
        return properties.getRedisKeyPrefix() + ":{run:" + runId + "}:confirm-tokens";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize confirm token", e);
        }
    }

    private ConfirmToken fromJson(String json) {
        try {
            return objectMapper.readValue(json, ConfirmToken.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid confirm token payload", e);
        }
    }

    private record ConfirmToken(String userId, String toolName, String argsHash, long expiresAtMs) {
    }
}
