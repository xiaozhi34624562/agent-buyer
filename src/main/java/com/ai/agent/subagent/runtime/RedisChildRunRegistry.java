package com.ai.agent.subagent.runtime;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.redis.RedisLuaScripts;
import com.ai.agent.subagent.model.ChildReleaseReason;
import com.ai.agent.subagent.model.ChildReserveRejectReason;
import com.ai.agent.subagent.model.ChildRunRef;
import com.ai.agent.subagent.model.ChildRunState;
import com.ai.agent.subagent.model.ParentLinkStatus;
import com.ai.agent.subagent.model.ReserveChildCommand;
import com.ai.agent.subagent.model.ReserveChildResult;
import com.ai.agent.tool.runtime.redis.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于Redis的子运行注册表实现。
 * <p>
 * 使用Redis Lua脚本保证子运行预留和释放操作的原子性，
 * 支持并发限制、预算控制等功能。
 * </p>
 */
@Component
public final class RedisChildRunRegistry implements ChildRunRegistry {
    private static final int CHILD_KEY_TTL_SECONDS = 7 * 24 * 60 * 60;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RESERVE_SCRIPT =
            RedisLuaScripts.load("redis/subagent/reserve-child.lua", List.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            RedisLuaScripts.load("redis/subagent/release-child.lua", Long.class);

    private final AgentProperties properties;
    private final RedisKeys keys;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChildRunRegistry(
            AgentProperties properties,
            RedisKeys keys,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.keys = keys;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReserveChildResult reserve(ReserveChildCommand command) {
        List<Object> result = redisTemplate.execute(
                RESERVE_SCRIPT,
                List.of(keys.children(command.parentRunId())),
                command.childRunId(),
                command.parentRunId(),
                command.parentToolCallId(),
                command.agentType(),
                Integer.toString(command.userTurnNo()),
                Integer.toString(properties.getSubAgent().getMaxSpawnPerRun()),
                Integer.toString(properties.getSubAgent().getMaxConcurrentPerRun()),
                Integer.toString(properties.getSubAgent().getSpawnBudgetPerUserTurn()),
                toJson(toRef(command)),
                Integer.toString(CHILD_KEY_TTL_SECONDS)
        );
        if (result == null || result.isEmpty()) {
            return ReserveChildResult.rejected(
                    command.parentRunId(),
                    command.childRunId(),
                    ChildReserveRejectReason.MAX_CONCURRENT_PER_RUN
            );
        }
        boolean accepted = Long.parseLong(result.get(0).toString()) == 1L;
        if (accepted) {
            boolean reused = result.size() > 3 && Long.parseLong(result.get(3).toString()) == 1L;
            return ReserveChildResult.accepted(command.parentRunId(), result.get(1).toString(), reused);
        }
        return ReserveChildResult.rejected(
                command.parentRunId(),
                command.childRunId(),
                ChildReserveRejectReason.valueOf(result.get(2).toString())
        );
    }

    @Override
    public boolean release(
            String parentRunId,
            String childRunId,
            ChildReleaseReason reason,
            ParentLinkStatus parentLinkStatus
    ) {
        Long result = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(keys.children(parentRunId)),
                childRunId,
                reason.name(),
                Integer.toString(CHILD_KEY_TTL_SECONDS),
                parentLinkStatus.name(),
                Long.toString(Instant.now().toEpochMilli())
        );
        return result != null && result == 1L;
    }

    @Override
    public List<ChildRunRef> findActiveChildren(String parentRunId) {
        List<Object> values = redisTemplate.opsForHash().values(keys.children(parentRunId));
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<ChildRunRef> active = new ArrayList<>();
        for (Object value : values) {
            if (value == null || !value.toString().startsWith("{")) {
                continue;
            }
            ChildRunRef child = readRef(value.toString());
            if (child.state() == ChildRunState.IN_FLIGHT && child.parentLinkStatus() == ParentLinkStatus.LIVE) {
                active.add(child);
            }
        }
        return List.copyOf(active);
    }

    private ChildRunRef toRef(ReserveChildCommand command) {
        return new ChildRunRef(
                command.parentRunId(),
                command.childRunId(),
                command.parentToolCallId(),
                command.agentType(),
                command.userTurnNo(),
                ChildRunState.IN_FLIGHT,
                ParentLinkStatus.LIVE,
                command.requestedAt().toEpochMilli(),
                null,
                null
        );
    }

    private ChildRunRef readRef(String json) {
        try {
            return objectMapper.readValue(json, ChildRunRef.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid redis child run ref", e);
        }
    }

    private String toJson(ChildRunRef ref) {
        try {
            return objectMapper.writeValueAsString(ref);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize child run ref", e);
        }
    }
}
