package com.ai.agent.tool.security;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 确认令牌存储器，用于管理工具执行前的用户确认令牌。
 *
 * <p>当工具返回PENDING_CONFIRM状态时生成确认令牌，用户确认后消费令牌以验证操作合法性。
 * 令牌绑定工具名称和参数哈希，防止参数篡改攻击。
 */
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

    /**
     * 创建确认令牌。
     *
     * <p>生成令牌并存储到Redis，绑定用户、工具名称和参数哈希。
     * 同时移除相同操作的已有令牌，确保每个操作只有一个有效令牌。
     *
     * @param runId 运行标识符
     * @param userId 用户标识符
     * @param toolName 工具名称
     * @param argsHash 参数哈希值
     * @return 生成的确认令牌
     */
    public String create(String runId, String userId, String toolName, String argsHash) {
        String token = Ids.newId("ct");
        ConfirmToken value = new ConfirmToken(userId, toolName, argsHash, Instant.now().plus(properties.getConfirmationTtl()).toEpochMilli());
        String key = key(runId);
        removeMatchingActionTokens(key, userId, toolName, argsHash);
        redisTemplate.opsForHash().put(key, token, toJson(value));
        redisTemplate.expire(key, properties.getConfirmationTtl());
        return token;
    }

    /**
     * 消费确认令牌，验证用户确认的合法性。
     *
     * <p>验证令牌是否存在、是否过期、是否匹配用户、工具名称和参数哈希。
     * 验证成功后删除令牌，确保一次性使用。
     *
     * @param runId 运行标识符
     * @param userId 用户标识符
     * @param token 确认令牌
     * @param toolName 工具名称
     * @param argsHash 参数哈希值
     * @return 验证成功返回true，否则返回false
     */
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

    /**
     * 清除运行的所有确认令牌。
     *
     * @param runId 运行标识符
     */
    public void clearRun(String runId) {
        redisTemplate.delete(key(runId));
    }

    /**
     * 移除匹配操作的已有令牌。
     *
     * @param key Redis键
     * @param userId 用户标识符
     * @param toolName 工具名称
     * @param argsHash 参数哈希值
     */
    private void removeMatchingActionTokens(String key, String userId, String toolName, String argsHash) {
        redisTemplate.opsForHash().entries(key).forEach((field, raw) -> {
            ConfirmToken stored = fromJson(raw.toString());
            if (stored.userId().equals(userId) && stored.toolName().equals(toolName) && stored.argsHash().equals(argsHash)) {
                redisTemplate.opsForHash().delete(key, field);
            }
        });
    }

    /**
     * 构建Redis键。
     *
     * @param runId 运行标识符
     * @return Redis键字符串
     */
    private String key(String runId) {
        return properties.getRedisKeyPrefix() + ":{run:" + runId + "}:confirm-tokens";
    }

    /**
     * 序列化对象为JSON字符串。
     *
     * @param value 待序列化对象
     * @return JSON字符串
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize confirm token", e);
        }
    }

    /**
     * 反序列化JSON为确认令牌对象。
     *
     * @param json JSON字符串
     * @return 确认令牌对象
     */
    private ConfirmToken fromJson(String json) {
        try {
            return objectMapper.readValue(json, ConfirmToken.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid confirm token payload", e);
        }
    }

    /**
     * 确认令牌内部记录，包含令牌绑定信息。
     */
    private record ConfirmToken(String userId, String toolName, String argsHash, long expiresAtMs) {
    }
}
