package com.ai.agent.api;

import com.ai.agent.tool.redis.RedisKeys;
import com.ai.agent.util.Ids;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public final class ContinuationLockService {
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
            redisTemplate.delete(redisKeys.continuationLock(lock.runId()));
        }
    }

    public void releaseRun(String runId) {
        redisTemplate.delete(redisKeys.continuationLock(runId));
    }

    public record Lock(String runId, String value) {
    }
}
