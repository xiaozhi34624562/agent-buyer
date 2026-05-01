package com.ai.agent.application;

import com.ai.agent.config.AgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public final class RedisRateLimiter {
    private final AgentProperties properties;
    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(AgentProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    public boolean allowRun(String userId) {
        String key = properties.getRedisKeyPrefix() + ":rate-limit:user:" + userId + ":runs-per-min";
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        return count != null && count <= properties.getRateLimit().getRunsPerUserPerMinute();
    }
}
