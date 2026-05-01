package com.ai.agent.tool.redis;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolCallRuntimeState;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.util.Ids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class LuaRedisToolStore implements RedisToolStore {
    private static final String INGEST_SCRIPT = """
            if redis.call('HSETNX', KEYS[3], ARGV[1], ARGV[2]) == 0 then
              return 0
            end
            local state = cjson.decode(ARGV[4])
            if redis.call('HEXISTS', KEYS[4], 'abort_requested') == 1 then
              state['status'] = 'CANCELLED'
              state['cancelReason'] = 'RUN_ABORTED'
              state['errorJson'] = ARGV[5]
              redis.call('HSET', KEYS[2], ARGV[2], cjson.encode(state))
              return 1
            end
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
            redis.call('HSET', KEYS[2], ARGV[2], ARGV[4])
            return 1
            """;

    private static final String SCHEDULE_SCRIPT = """
            if redis.call('HEXISTS', KEYS[4], 'abort_requested') == 1 then
              return {}
            end
            local ids = redis.call('ZRANGE', KEYS[1], 0, tonumber(ARGV[4]) - 1)
            local running = 0
            local runningUnsafe = false
            for _, id in ipairs(ids) do
              local raw = redis.call('HGET', KEYS[2], id)
              if raw then
                local state = cjson.decode(raw)
                if state['status'] == 'RUNNING' then
                  running = running + 1
                  if state['call']['isConcurrent'] == false then
                    runningUnsafe = true
                  end
                end
              end
            end
            if runningUnsafe then
              return {}
            end
            local capacity = tonumber(ARGV[3]) - running
            if capacity <= 0 then
              return {}
            end
            local started = {}
            local startedCount = 0
            local now = tonumber(ARGV[1])
            for _, id in ipairs(ids) do
              local raw = redis.call('HGET', KEYS[2], id)
              if raw then
                local state = cjson.decode(raw)
                local status = state['status']
                if status == 'WAITING' then
                  local leaseMs = tonumber(ARGV[2])
                  local rawCallTimeoutMs = state['call']['timeoutMs']
                  local callTimeoutMs = 0
                  if rawCallTimeoutMs ~= nil and rawCallTimeoutMs ~= cjson.null then
                    callTimeoutMs = tonumber(rawCallTimeoutMs) or 0
                  end
                  if callTimeoutMs > leaseMs then
                    leaseMs = callTimeoutMs
                  end
                  local leaseUntil = now + leaseMs
                  local safe = state['call']['isConcurrent']
                  if safe == false then
                    if running == 0 and startedCount == 0 then
                      state['status'] = 'RUNNING'
                      state['attempt'] = (state['attempt'] or 0) + 1
                      state['leaseToken'] = ARGV[6] .. ':' .. id .. ':' .. tostring(state['attempt'])
                      state['leaseUntil'] = leaseUntil
                      state['workerId'] = ARGV[5]
                      local encoded = cjson.encode(state)
                      redis.call('HSET', KEYS[2], id, encoded)
                      redis.call('ZADD', KEYS[3], leaseUntil, id)
                      table.insert(started, encoded)
                    end
                    return started
                  else
                    if capacity > 0 then
                      state['status'] = 'RUNNING'
                      state['attempt'] = (state['attempt'] or 0) + 1
                      state['leaseToken'] = ARGV[6] .. ':' .. id .. ':' .. tostring(state['attempt'])
                      state['leaseUntil'] = leaseUntil
                      state['workerId'] = ARGV[5]
                      local encoded = cjson.encode(state)
                      redis.call('HSET', KEYS[2], id, encoded)
                      redis.call('ZADD', KEYS[3], leaseUntil, id)
                      table.insert(started, encoded)
                      startedCount = startedCount + 1
                      capacity = capacity - 1
                    else
                      return started
                    end
                  end
                end
              end
            end
            return started
            """;

    private static final String COMPLETE_SCRIPT = """
            local raw = redis.call('HGET', KEYS[1], ARGV[1])
            if not raw then
              return 0
            end
            local state = cjson.decode(raw)
            local terminal = cjson.decode(ARGV[4])
            if redis.call('HEXISTS', KEYS[3], 'abort_requested') == 1
              and terminal['status'] ~= 'CANCELLED'
              and state['call']['idempotent'] == true then
              return 0
            end
            if state['status'] ~= 'RUNNING' then
              return 0
            end
            if tostring(state['attempt']) ~= ARGV[2] then
              return 0
            end
            if state['leaseToken'] ~= ARGV[3] then
              return 0
            end
            state['status'] = terminal['status']
            state['resultJson'] = terminal['resultJson']
            state['errorJson'] = terminal['errorJson']
            state['cancelReason'] = terminal['cancelReason']
            redis.call('HSET', KEYS[1], ARGV[1], cjson.encode(state))
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """;

    private static final String REAP_EXPIRED_LEASES_SCRIPT = """
            local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local terminals = {}
            for _, id in ipairs(ids) do
              local raw = redis.call('HGET', KEYS[2], id)
              if raw then
                local state = cjson.decode(raw)
                local leaseUntil = tonumber(state['leaseUntil'] or '0')
                if state['status'] == 'RUNNING' and leaseUntil <= tonumber(ARGV[1]) then
                  redis.call('ZREM', KEYS[1], id)
                  if state['call']['idempotent'] == true then
                    state['status'] = 'WAITING'
                    state['leaseToken'] = cjson.null
                    state['leaseUntil'] = cjson.null
                    state['workerId'] = cjson.null
                    redis.call('HSET', KEYS[2], id, cjson.encode(state))
                  else
                    state['status'] = 'CANCELLED'
                    state['cancelReason'] = 'TOOL_TIMEOUT'
                    state['errorJson'] = ARGV[2]
                    redis.call('HSET', KEYS[2], id, cjson.encode(state))
                    table.insert(terminals, cjson.encode({
                      toolCallId = id,
                      status = 'CANCELLED',
                      resultJson = cjson.null,
                      errorJson = ARGV[2],
                      cancelReason = 'TOOL_TIMEOUT',
                      synthetic = true
                    }))
                  end
                else
                  redis.call('ZREM', KEYS[1], id)
                end
              else
                redis.call('ZREM', KEYS[1], id)
              end
            end
            return terminals
            """;

    private static final String CANCEL_WAITING_SCRIPT = """
            local ids = redis.call('ZRANGE', KEYS[1], 0, -1)
            local terminals = {}
            for _, id in ipairs(ids) do
              local raw = redis.call('HGET', KEYS[2], id)
              if raw then
                local state = cjson.decode(raw)
                if state['status'] == 'WAITING' then
                  state['status'] = 'CANCELLED'
                  state['cancelReason'] = ARGV[1]
                  state['errorJson'] = ARGV[2]
                  redis.call('HSET', KEYS[2], id, cjson.encode(state))
                  table.insert(terminals, cjson.encode({
                    toolCallId = id,
                    status = 'CANCELLED',
                    resultJson = cjson.null,
                    errorJson = ARGV[2],
                    cancelReason = ARGV[1],
                    synthetic = true
                  }))
                end
              end
            end
            return terminals
            """;

    private static final String CANCEL_ACTIVE_SCRIPT = """
            local ids = redis.call('ZRANGE', KEYS[1], 0, -1)
            local terminals = {}
            for _, id in ipairs(ids) do
              local raw = redis.call('HGET', KEYS[2], id)
              if raw then
                local state = cjson.decode(raw)
                if state['status'] == 'WAITING' or (state['status'] == 'RUNNING' and state['call']['idempotent'] == true) then
                  state['status'] = 'CANCELLED'
                  state['cancelReason'] = ARGV[1]
                  state['errorJson'] = ARGV[2]
                  redis.call('HSET', KEYS[2], id, cjson.encode(state))
                  redis.call('ZREM', KEYS[3], id)
                  table.insert(terminals, cjson.encode({
                    toolCallId = id,
                    status = 'CANCELLED',
                    resultJson = cjson.null,
                    errorJson = ARGV[2],
                    cancelReason = ARGV[1],
                    synthetic = true
                  }))
                end
              end
            end
            return terminals
            """;

    private final AgentProperties properties;
    private final RedisKeys keys;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String workerId;

    public LuaRedisToolStore(
            AgentProperties properties,
            RedisKeys keys,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String workerId
    ) {
        this.properties = properties;
        this.keys = keys;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.workerId = workerId;
    }

    @Override
    public boolean ingestWaiting(String runId, ToolCall call) {
        ToolCallRuntimeState state = new ToolCallRuntimeState(
                call,
                ToolStatus.WAITING,
                0,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(INGEST_SCRIPT, Long.class),
                List.of(keys.queue(runId), keys.tools(runId), keys.toolUseIds(runId), keys.meta(runId)),
                call.toolUseId(),
                call.toolCallId(),
                Long.toString(call.seq()),
                toJson(state),
                "{\"type\":\"run_aborted\",\"message\":\"tool call ingested after run abort\"}"
        );
        boolean ingested = result != null && result == 1L;
        if (ingested) {
            redisTemplate.opsForSet().add(keys.activeRuns(), runId);
        }
        return ingested;
    }

    @Override
    public List<StartedTool> schedule(String runId) {
        List<String> states = redisTemplate.execute(
                new DefaultRedisScript<>(SCHEDULE_SCRIPT, List.class),
                List.of(keys.queue(runId), keys.tools(runId), keys.leases(runId), keys.meta(runId)),
                Long.toString(Instant.now().toEpochMilli()),
                Long.toString(properties.getLeaseMs()),
                Integer.toString(properties.getMaxParallel()),
                Integer.toString(properties.getMaxScan()),
                workerId,
                Ids.newId("lease")
        );
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        return states.stream()
                .map(this::readState)
                .map(state -> new StartedTool(
                        state.call(),
                        state.attempt(),
                        state.leaseToken(),
                        state.leaseUntil(),
                        state.workerId()
                ))
                .toList();
    }

    @Override
    public boolean complete(StartedTool running, ToolTerminal terminal) {
        Long result = redisTemplate.execute(
                new DefaultRedisScript<>(COMPLETE_SCRIPT, Long.class),
                List.of(keys.tools(running.call().runId()), keys.leases(running.call().runId()), keys.meta(running.call().runId())),
                running.call().toolCallId(),
                Integer.toString(running.attempt()),
                running.leaseToken(),
                toJson(terminal)
        );
        return result != null && result == 1L;
    }

    @Override
    public List<ToolTerminal> reapExpiredLeases(String runId, long nowMillis) {
        List<String> terminals = redisTemplate.execute(
                new DefaultRedisScript<>(REAP_EXPIRED_LEASES_SCRIPT, List.class),
                List.of(keys.leases(runId), keys.tools(runId)),
                Long.toString(nowMillis),
                "{\"type\":\"tool_timeout\",\"message\":\"tool lease expired\"}"
        );
        if (terminals == null) {
            return List.of();
        }
        return terminals.stream().map(this::readTerminal).toList();
    }

    @Override
    public List<ToolTerminal> cancelWaiting(String runId, CancelReason reason) {
        List<String> terminals = redisTemplate.execute(
                new DefaultRedisScript<>(CANCEL_WAITING_SCRIPT, List.class),
                List.of(keys.queue(runId), keys.tools(runId)),
                reason.name(),
                "{\"type\":\"cancelled\",\"reason\":\"" + reason.name() + "\"}"
        );
        if (terminals == null) {
            return List.of();
        }
        return terminals.stream().map(this::readTerminal).toList();
    }

    @Override
    public Optional<ToolTerminal> terminal(String runId, String toolCallId) {
        Object raw = redisTemplate.opsForHash().get(keys.tools(runId), toolCallId);
        if (raw == null) {
            return Optional.empty();
        }
        ToolCallRuntimeState state = readState(raw.toString());
        if (state.status() == ToolStatus.WAITING || state.status() == ToolStatus.RUNNING) {
            return Optional.empty();
        }
        return Optional.of(new ToolTerminal(
                state.call().toolCallId(),
                state.status(),
                state.resultJson(),
                state.errorJson(),
                state.cancelReason(),
                state.status() == ToolStatus.CANCELLED && state.cancelReason() != null
        ));
    }

    @Override
    public Set<String> activeRunIds() {
        Set<String> runIds = redisTemplate.opsForSet().members(keys.activeRuns());
        if (runIds == null || runIds.isEmpty()) {
            return Set.of();
        }
        Set<String> active = new LinkedHashSet<>();
        for (String runId : runIds) {
            if (hasPendingOrRunningTools(runId)) {
                active.add(runId);
            } else {
                redisTemplate.opsForSet().remove(keys.activeRuns(), runId);
            }
        }
        return active;
    }

    @Override
    public List<ToolTerminal> abort(String runId, String reason) {
        redisTemplate.opsForHash().put(keys.meta(runId), "abort_requested", reason == null ? "true" : reason);
        List<String> terminals = redisTemplate.execute(
                new DefaultRedisScript<>(CANCEL_ACTIVE_SCRIPT, List.class),
                List.of(keys.queue(runId), keys.tools(runId), keys.leases(runId)),
                abortReason(reason).name(),
                "{\"type\":\"cancelled\",\"reason\":\"" + (reason == null ? "abort_requested" : reason) + "\"}"
        );
        if (terminals == null) {
            return List.of();
        }
        return terminals.stream().map(this::readTerminal).toList();
    }

    @Override
    public boolean abortRequested(String runId) {
        Object value = redisTemplate.opsForHash().get(keys.meta(runId), "abort_requested");
        return value != null;
    }

    private CancelReason abortReason(String reason) {
        if ("run_wallclock_timeout".equals(reason)) {
            return CancelReason.RUN_TIMEOUT;
        }
        if ("tool_result_timeout".equals(reason)) {
            return CancelReason.TOOL_TIMEOUT;
        }
        if ("user_abort".equals(reason)) {
            return CancelReason.USER_ABORT;
        }
        return CancelReason.RUN_ABORTED;
    }

    private ToolCallRuntimeState readState(String json) {
        try {
            return objectMapper.readValue(json, ToolCallRuntimeState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid redis tool state", e);
        }
    }

    private boolean hasPendingOrRunningTools(String runId) {
        List<Object> values = redisTemplate.opsForHash().values(keys.tools(runId));
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (Object value : values) {
            ToolCallRuntimeState state = readState(value.toString());
            if (state.status() == ToolStatus.WAITING || state.status() == ToolStatus.RUNNING) {
                return true;
            }
        }
        return false;
    }

    private ToolTerminal readTerminal(String json) {
        try {
            return objectMapper.readValue(json, ToolTerminal.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid redis terminal state", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize redis value", e);
        }
    }
}
