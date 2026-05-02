package com.ai.agent.application;

import com.ai.agent.config.AgentProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis限流器
 * <p>
 * 基于Redis实现的用户级限流组件，用于控制单个用户每分钟的Agent运行次数，
 * 防止用户过度调用导致系统资源耗尽。
 * </p>
 */
@Component
public final class RedisRateLimiter {
    private final AgentProperties properties;
    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(AgentProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 判断是否允许用户发起运行
     * <p>
     * 通过Redis计数器实现每分钟限流，计数器在首次访问时设置1分钟过期时间。
     * </p>
     *
     * @param userId 用户ID
     * @return true表示允许运行，false表示已超限
     */
    public boolean allowRun(String userId) {
        String key = properties.getRedisKeyPrefix() + ":rate-limit:user:" + userId + ":runs-per-min";
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        return count != null && count <= properties.getRateLimit().getRunsPerUserPerMinute();
    }
}
