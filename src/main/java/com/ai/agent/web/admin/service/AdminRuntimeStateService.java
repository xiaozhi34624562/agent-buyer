package com.ai.agent.web.admin.service;

import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.ai.agent.web.admin.dto.AdminRuntimeStateDto;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
public class AdminRuntimeStateService {

    private static final Set<String> EXCLUDED_CONTROL_FIELDS = Set.of("confirmToken", "abortToken");

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
        entries.put("meta", getHashEntries(redisKeys.meta(runId)));
        entries.put("queue", getZsetEntries(redisKeys.queue(runId)));
        entries.put("tools", getSetEntries(redisKeys.tools(runId)));
        entries.put("leases", getHashEntries(redisKeys.leases(runId)));
        entries.put("control", getControlEntries(runId));
        entries.put("children", getSetEntries(redisKeys.children(runId)));
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

    private Map<String, String> getZsetEntries(String key) {
        Set<String> members = redisTemplate.opsForZSet().range(key, 0, -1);
        if (members == null || members.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, String> result = new LinkedHashMap<>();
        members.forEach(m -> {
            Double score = redisTemplate.opsForZSet().score(key, m);
            result.put(m, score != null ? String.valueOf(score.longValue()) : null);
        });
        return result;
    }

    private Set<String> getSetEntries(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        return members != null ? members : Set.of();
    }

    private String getStringEntry(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    private Map<String, String> getControlEntries(String runId) {
        Map<String, String> rawControl = getHashEntries(redisKeys.control(runId));
        Map<String, String> filtered = new LinkedHashMap<>();
        rawControl.forEach((k, v) -> {
            if (!EXCLUDED_CONTROL_FIELDS.contains(k)) {
                filtered.put(k, v);
            }
        });
        return filtered;
    }
}