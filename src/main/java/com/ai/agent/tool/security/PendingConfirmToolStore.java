package com.ai.agent.tool.security;

import com.ai.agent.config.AgentProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Stores pending tool call context when a tool returns PENDING_CONFIRM.
 * Used to automatically execute the confirmed tool call when user confirms,
 * avoiding the need for LLM to copy the confirmToken.
 */
@Component
public final class PendingConfirmToolStore {
    private final AgentProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PendingConfirmToolStore(AgentProperties properties, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Save pending tool call context when PENDING_CONFIRM is returned.
     *
     * @param runId run identifier
     * @param toolCallId the tool call ID that returned PENDING_CONFIRM
     * @param toolName the tool name (e.g., cancel_order)
     * @param argsJson the original arguments JSON (without confirmToken)
     * @param confirmToken the confirmToken that was generated
     * @param summary the summary text to display to user
     */
    public void save(String runId, String toolCallId, String toolName, String argsJson, String confirmToken, String summary) {
        PendingConfirmTool value = new PendingConfirmTool(
                toolCallId,
                toolName,
                argsJson,
                confirmToken,
                summary,
                Instant.now().plus(properties.getConfirmationTtl()).toEpochMilli()
        );
        String key = key(runId);
        redisTemplate.opsForValue().set(key, toJson(value), properties.getConfirmationTtl());
    }

    /**
     * Retrieve the pending tool call context for a run.
     *
     * @param runId run identifier
     * @return the pending tool call context, or null if not found or expired
     */
    public PendingConfirmTool load(String runId) {
        String key = key(runId);
        String raw = redisTemplate.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        PendingConfirmTool stored = fromJson(raw);
        if (stored.expiresAtMs() < Instant.now().toEpochMilli()) {
            redisTemplate.delete(key);
            return null;
        }
        return stored;
    }

    /**
     * Consume and delete the pending tool call context.
     *
     * @param runId run identifier
     * @return the pending tool call context, or null if not found
     */
    public PendingConfirmTool consume(String runId) {
        PendingConfirmTool stored = load(runId);
        if (stored != null) {
            redisTemplate.delete(key(runId));
        }
        return stored;
    }

    /**
     * Clear the pending tool call for a run.
     *
     * @param runId run identifier
     */
    public void clearRun(String runId) {
        redisTemplate.delete(key(runId));
    }

    private String key(String runId) {
        return properties.getRedisKeyPrefix() + ":{run:" + runId + "}:pending-confirm-tool";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize pending confirm tool", e);
        }
    }

    private PendingConfirmTool fromJson(String json) {
        try {
            return objectMapper.readValue(json, PendingConfirmTool.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid pending confirm tool payload", e);
        }
    }

    /**
     * Represents a pending tool call that needs user confirmation.
     */
    public record PendingConfirmTool(
            String toolCallId,
            String toolName,
            String argsJson,
            String confirmToken,
            String summary,
            long expiresAtMs
    ) {}
}