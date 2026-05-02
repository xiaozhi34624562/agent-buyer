package com.ai.agent.application;

import com.ai.agent.redis.RedisLuaScripts;
import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.ai.agent.util.Ids;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 继续运行锁服务
 * <p>
 * 基于Redis实现的分布式锁服务，用于控制运行的继续执行权限，
 * 防止同一运行被并发继续执行，确保运行状态的一致性。
 * </p>
 */
@Component
public final class ContinuationLockService {
    private static final DefaultRedisScript<Long> RELEASE_IF_TOKEN_MATCHES =
            RedisLuaScripts.load("redis/continuation/release-if-token-matches.lua", Long.class);

    private final RedisKeys redisKeys;
    private final StringRedisTemplate redisTemplate;

    public ContinuationLockService(RedisKeys redisKeys, StringRedisTemplate redisTemplate) {
        this.redisKeys = redisKeys;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取继续运行锁
     * <p>
     * 使用Redis的setIfAbsent实现分布式锁，锁有效期为5分钟。
     * </p>
     *
     * @param runId 运行ID
     * @return 锁对象，获取失败时返回null
     */
    public Lock acquire(String runId) {
        String value = Ids.newId("lock");
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(redisKeys.continuationLock(runId), value, Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(ok)) {
            return null;
        }
        return new Lock(runId, value);
    }

    /**
     * 释放继续运行锁
     * <p>
     * 使用Lua脚本确保只有锁持有者才能释放锁，防止误释放。
     * </p>
     *
     * @param lock 锁对象，为null时不执行任何操作
     */
    public void release(Lock lock) {
        if (lock != null) {
            redisTemplate.execute(
                    RELEASE_IF_TOKEN_MATCHES,
                    List.of(redisKeys.continuationLock(lock.runId())),
                    lock.value()
            );
        }
    }

    /**
     * 强制释放指定运行的锁
     * <p>
     * 直接删除Redis中的锁键，不检查锁持有者，用于中止运行时强制释放。
     * </p>
     *
     * @param runId 运行ID
     */
    public void releaseRun(String runId) {
        redisTemplate.delete(redisKeys.continuationLock(runId));
    }

    /**
     * 继续运行锁对象
     * <p>
     * 包含运行ID和锁的唯一标识值，用于后续释放锁时验证持有者身份。
     * </p>
     */
    public record Lock(String runId, String value) {
    }
}
