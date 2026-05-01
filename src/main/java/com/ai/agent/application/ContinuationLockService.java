package com.ai.agent.application;

import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.ai.agent.util.Ids;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public final class ContinuationLockService {
    private static final DefaultRedisScript<Long> RELEASE_IF_TOKEN_MATCHES = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final RedisKeys redisKeys;
    private final StringRedisTemplate redisTemplate;

    public ContinuationLockService(RedisKeys redisKeys, StringRedisTemplate redisTemplate) {
        this.redisKeys = redisKeys;
        this.redisTemplate = redisTemplate;
    }

    public Lock acquire(String runId) {
        String value = Ids.newId("lock");
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(redisKeys.continuationLock(runId), value, Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(ok)) {
            return null;
        }
        return new Lock(runId, value);
    }

    public void release(Lock lock) {
        if (lock != null) {
            redisTemplate.execute(
                    RELEASE_IF_TOKEN_MATCHES,
                    List.of(redisKeys.continuationLock(lock.runId())),
                    lock.value()
            );
        }
    }

    public void releaseRun(String runId) {
        redisTemplate.delete(redisKeys.continuationLock(runId));
    }

    public record Lock(String runId, String value) {
    }
}
