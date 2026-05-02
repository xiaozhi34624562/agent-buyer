package com.ai.agent.web.admin.service;

import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.ai.agent.web.admin.dto.AdminRuntimeStateDto;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Admin 控制台运行时状态查询服务。
 * <p>
 * 从 Redis 读取 Agent 运行状态，用于调试和监控：
 * - 工具调用队列和执行进度
 * - LLM 调用追踪
 * - 子运行关系
 * - Todo 列表管理
 * - 运行活跃状态
 * </p>
 * <p>
 * 安全考虑：敏感字段（confirmToken、abortToken）不对外暴露。
 * 所有 Redis 操作包含错误处理，避免因 key 不存在或类型不匹配导致异常。
 * </p>
 */
@Service
public class AdminRuntimeStateService {

    /**
     * 不对外暴露的敏感字段。
     * 这些 token 可用于执行未授权操作，必须过滤。
     */
    private static final Set<String> EXCLUDED_META_FIELDS = Set.of("confirmToken", "abortToken");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeys redisKeys;

    /**
     * 构造服务实例。
     *
     * @param redisTemplate Redis 操作模板
     * @param redisKeys     Redis key 生成器
     */
    public AdminRuntimeStateService(StringRedisTemplate redisTemplate, RedisKeys redisKeys) {
        this.redisTemplate = redisTemplate;
        this.redisKeys = redisKeys;
    }

    /**
     * 获取指定运行的完整运行时状态。
     * <p>
     * 返回 Redis 状态投影，包含：
     * - meta: 运行元数据（状态、时间戳，不含敏感 token）
     * - queue: 待执行工具调用（ZSET）
     * - tools: 活跃工具调用状态（HASH）
     * - leases: 工具执行租约（ZSET）
     * - children: 子运行引用（HASH）
     * - todos: Todo 列表项（HASH）
     * - todo-reminder: Todo 提醒时间戳（STRING）
     * </p>
     * <p>
     * 安全或复杂度考虑，以下字段不返回：
     * - llm-call-budget: 可能暴露用量模式
     * - continuation-lock: 短暂的锁状态
     * - tool-use-ids: 与 tools hash 重复
     * - 完整 active-runs 集合: 仅返回布尔值表示是否在集合中
     * </p>
     *
     * @param runId 运行标识
     * @return 运行时状态 DTO
     */
    public AdminRuntimeStateDto getRuntimeState(String runId) {
        boolean activeRun = isActiveRun(runId);

        Map<String, Object> entries = new LinkedHashMap<>();

        // Fixed keys projection - no arbitrary Redis key input
        // Note: tools is HASH (toolCallId -> stateJson), leases is ZSET (leaseUntil -> toolCallId),
        // children is HASH (childKey -> refJson), interrupt/abort are in meta not control
        entries.put("meta", getMetaEntries(redisKeys.meta(runId)));
        entries.put("queue", getZsetEntries(redisKeys.queue(runId)));
        entries.put("tools", getHashEntries(redisKeys.tools(runId)));
        entries.put("leases", getZsetEntries(redisKeys.leases(runId)));
        entries.put("children", getHashEntries(redisKeys.children(runId)));
        entries.put("todos", getHashEntries(redisKeys.todos(runId)));
        entries.put("todo-reminder", getStringEntry(redisKeys.todoReminder(runId)));

        // Note: llm-call-budget is intentionally NOT included
        // Note: continuation-lock is intentionally NOT included
        // Note: tool-use-ids is intentionally NOT included
        // Note: complete agent:active-runs set is NOT returned - only activeRun boolean

        return new AdminRuntimeStateDto(runId, activeRun, entries);
    }

    /**
     * 检查运行是否在活跃运行集合中。
     *
     * @param runId 运行标识
     * @return 是否为活跃运行
     */
    private boolean isActiveRun(String runId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(redisKeys.activeRuns(), runId));
    }

    /**
     * 从 Redis HASH key 获取所有字段。
     * <p>
     * 容错处理：当 key 不存在或类型不匹配时返回空 map，
     * 避免 WRONGTYPE 错误影响整体查询。
     * </p>
     *
     * @param key Redis key
     * @return 字段名到值的映射，key 不存在或类型不匹配时返回空 map
     */
    private Map<String, String> getHashEntries(String key) {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) {
                return new HashMap<>();
            }
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
            return result;
        } catch (org.springframework.data.redis.RedisSystemException e) {
            // Key may not exist or have wrong type - return empty map
            return new HashMap<>();
        }
    }

    /**
     * 从 Redis HASH key 获取元数据，过滤敏感字段。
     * <p>
     * 过滤 confirmToken 和 abortToken，防止未授权操作。
     * 容错处理：当 key 不存在或类型不匹配时返回空 map。
     * </p>
     *
     * @param key 元数据 Redis key
     * @return 过滤后的元数据映射
     */
    private Map<String, String> getMetaEntries(String key) {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
            if (raw == null || raw.isEmpty()) {
                return new HashMap<>();
            }
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                String keyStr = String.valueOf(k);
                if (!EXCLUDED_META_FIELDS.contains(keyStr)) {
                    result.put(keyStr, v != null ? String.valueOf(v) : null);
                }
            });
            return result;
        } catch (org.springframework.data.redis.RedisSystemException e) {
            // Key may not exist or have wrong type - return empty map
            return new HashMap<>();
        }
    }

    /**
     * 从 Redis ZSET key 获取所有元素及其分数。
     * <p>
     * 使用 rangeWithScores 单次调用获取完整数据，避免 N+1 查询模式。
     * 容错处理：当 key 不存在或类型不匹配时返回空 map。
     * </p>
     *
     * @param key Redis key
     * @return 值到分数字符串的映射
     */
    private Map<String, String> getZsetEntries(String key) {
        try {
            // Use rangeWithScores to avoid N+1 pattern - single Redis call instead of 1 + N calls
            Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
            if (tuples == null || tuples.isEmpty()) {
                return new HashMap<>();
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Double score = tuple.getScore();
                result.put(tuple.getValue(), score != null ? String.valueOf(score.longValue()) : null);
            }
            return result;
        } catch (org.springframework.data.redis.RedisSystemException e) {
            // Key may not exist or have wrong type - return empty map
            return new HashMap<>();
        }
    }

    /**
     * 从 Redis STRING key 获取单个字符串值。
     * <p>
     * 容错处理：当 key 不存在或类型不匹配时返回 null。
     * </p>
     *
     * @param key Redis key
     * @return 字符串值，key 不存在或类型不匹配时返回 null
     */
    private String getStringEntry(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (org.springframework.data.redis.RedisSystemException e) {
            // Key may not exist or have wrong type - return null
            return null;
        }
    }
}