package com.ai.agent.api;

import com.ai.agent.tool.redis.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class RedisRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local limit = tonumber(ARGV[1])
            if current >= limit then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
              return -current
            end
            local next = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return next
            """, Long.class);
    private static final long BUDGET_KEY_TTL_SECONDS = 7L * 24 * 60 * 60;

    private final RedisKeys redisKeys;
    private final StringRedisTemplate redisTemplate;

    public RedisRunLlmCallBudgetStore(RedisKeys redisKeys, StringRedisTemplate redisTemplate) {
        this.redisKeys = redisKeys;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Reservation reserveRunCall(String runId, int limit) {
        Long value = redisTemplate.execute(
                RESERVE_SCRIPT,
                List.of(redisKeys.llmCallBudget(runId)),
                String.valueOf(limit),
                String.valueOf(BUDGET_KEY_TTL_SECONDS)
        );
        long used = value == null ? 0L : Math.abs(value);
        return new Reservation(value != null && value > 0, used);
    }
}
