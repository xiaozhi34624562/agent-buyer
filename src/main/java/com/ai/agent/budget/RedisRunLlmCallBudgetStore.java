package com.ai.agent.budget;

import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.ai.agent.redis.RedisLuaScripts;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于Redis的LLM调用预算存储实现
 *
 * <p>使用Redis Lua脚本实现原子性的预算预留操作，保证分布式环境下的并发安全</p>
 */
@Component
public final class RedisRunLlmCallBudgetStore implements RunLlmCallBudgetStore {
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            RedisLuaScripts.load("redis/budget/reserve-run-llm-call.lua", Long.class);
    /** 预算键的过期时间（秒），默认7天 */
    private static final long BUDGET_KEY_TTL_SECONDS = 7L * 24 * 60 * 60;

    private final RedisKeys redisKeys;
    private final StringRedisTemplate redisTemplate;

    /**
     * 构造Redis预算存储
     *
     * @param redisKeys Redis键管理器
     * @param redisTemplate Redis操作模板
     */
    public RedisRunLlmCallBudgetStore(RedisKeys redisKeys, StringRedisTemplate redisTemplate) {
        this.redisKeys = redisKeys;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 预留一次LLM调用
     *
     * <p>通过Redis Lua脚本原子性地检查并增加调用计数，确保分布式环境下的预算控制</p>
     *
     * @param runId 运行ID
     * @param limit 调用次数上限
     * @return 预留结果，accepted为true表示预留成功，used为当前已使用次数
     */
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