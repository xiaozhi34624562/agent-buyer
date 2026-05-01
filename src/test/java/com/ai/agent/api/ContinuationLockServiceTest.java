package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.redis.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContinuationLockServiceTest {
    @Test
    void releaseDoesNotDeleteLockWhenTokenDoesNotMatch() {
        String runId = "run-token-race";
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        ContinuationLockService lockService = new ContinuationLockService(
                new RedisKeys(new AgentProperties()),
                redisTemplate
        );
        redisTemplate.putLock(runId, "newer-token");

        lockService.release(new ContinuationLockService.Lock(runId, "older-token"));

        assertThat(redisTemplate.lockValue(runId)).isEqualTo("newer-token");
    }

    @Test
    void releaseDeletesLockWhenTokenMatches() {
        String runId = "run-token-match";
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        ContinuationLockService lockService = new ContinuationLockService(
                new RedisKeys(new AgentProperties()),
                redisTemplate
        );
        redisTemplate.putLock(runId, "current-token");

        lockService.release(new ContinuationLockService.Lock(runId, "current-token"));

        assertThat(redisTemplate.lockValue(runId)).isNull();
    }

    private static final class FakeStringRedisTemplate extends StringRedisTemplate {
        private final Map<String, String> locksByRun = new HashMap<>();

        void putLock(String runId, String value) {
            locksByRun.put(runId, value);
        }

        String lockValue(String runId) {
            return locksByRun.get(runId);
        }

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            String runId = runIdFromKey(keys.get(0));
            if (args.length > 0 && args[0].equals(locksByRun.get(runId))) {
                locksByRun.remove(runId);
                return (T) Long.valueOf(1L);
            }
            return (T) Long.valueOf(0L);
        }

        @Override
        public Boolean delete(String key) {
            locksByRun.remove(runIdFromKey(key));
            return true;
        }

        private static String runIdFromKey(String key) {
            int start = key.indexOf("{run:");
            int end = key.indexOf("}", start);
            if (start < 0 || end < 0) {
                return key;
            }
            return key.substring(start + 5, end);
        }
    }
}
