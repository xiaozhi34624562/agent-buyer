package com.ai.agent.subagent;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.redis.RedisKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public final class RedisChildRunRegistry implements ChildRunRegistry {
    private static final int CHILD_KEY_TTL_SECONDS = 7 * 24 * 60 * 60;

    private static final String RESERVE_SCRIPT = """
            local childField = 'child:' .. ARGV[1]
            local toolCallField = 'tool_call:' .. ARGV[3]
            local existingChildId = redis.call('HGET', KEYS[1], toolCallField)
            if existingChildId then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
              return {1, existingChildId, '', 1}
            end
            local existing = redis.call('HGET', KEYS[1], childField)
            if existing then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
              return {1, ARGV[1], '', 1}
            end

            local spawned = tonumber(redis.call('HGET', KEYS[1], 'spawned_total') or '0')
            local inFlight = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
            local turnField = 'turn:' .. ARGV[5] .. ':spawn_attempts'
            local turnSpawned = tonumber(redis.call('HGET', KEYS[1], turnField) or '0')

            if turnSpawned >= tonumber(ARGV[8]) then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
              return {0, ARGV[1], 'SPAWN_BUDGET_PER_USER_TURN', 0}
            end
            if spawned >= tonumber(ARGV[6]) then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
              return {0, ARGV[1], 'MAX_SPAWN_PER_RUN', 0}
            end
            if inFlight >= tonumber(ARGV[7]) then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
              return {0, ARGV[1], 'MAX_CONCURRENT_PER_RUN', 0}
            end

            redis.call('HINCRBY', KEYS[1], 'spawned_total', 1)
            redis.call('HINCRBY', KEYS[1], 'in_flight', 1)
            redis.call('HINCRBY', KEYS[1], turnField, 1)
            redis.call('HSET', KEYS[1], childField, ARGV[9])
            redis.call('HSET', KEYS[1], toolCallField, ARGV[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
            return {1, ARGV[1], '', 0}
            """;

    private static final String RELEASE_SCRIPT = """
            local childField = 'child:' .. ARGV[1]
            local raw = redis.call('HGET', KEYS[1], childField)
            if not raw then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
              return 0
            end
            local child = cjson.decode(raw)
            if child['state'] ~= 'IN_FLIGHT' then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
              return 0
            end
            local inFlight = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
            if inFlight > 0 then
              redis.call('HINCRBY', KEYS[1], 'in_flight', -1)
            end
            child['state'] = 'RELEASED'
            child['releaseReason'] = ARGV[2]
            child['parentLinkStatus'] = ARGV[4]
            child['releasedAtEpochMs'] = tonumber(ARGV[5])
            redis.call('HSET', KEYS[1], childField, cjson.encode(child))
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """;

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
                new DefaultRedisScript<>(RESERVE_SCRIPT, List.class),
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
                new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class),
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
