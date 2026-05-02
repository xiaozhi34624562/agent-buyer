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

@Service
public class AdminRuntimeStateService {

    // Sensitive fields in meta that should not be exposed to console
    private static final Set<String> EXCLUDED_META_FIELDS = Set.of("confirmToken", "abortToken");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeys redisKeys;

    public AdminRuntimeStateService(StringRedisTemplate redisTemplate, RedisKeys redisKeys) {
        this.redisTemplate = redisTemplate;
        this.redisKeys = redisKeys;
    }

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

    private boolean isActiveRun(String runId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(redisKeys.activeRuns(), runId));
    }

    private Map<String, String> getHashEntries(String key) {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        if (raw == null || raw.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v != null ? String.valueOf(v) : null));
        return result;
    }

    private Map<String, String> getMetaEntries(String key) {
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
    }

    private Map<String, String> getZsetEntries(String key) {
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
    }

    private String getStringEntry(String key) {
        return redisTemplate.opsForValue().get(key);
    }
}