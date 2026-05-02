package com.ai.agent.tool.runtime.redis;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.redis.RedisLuaScripts;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolCallRuntimeState;
import com.ai.agent.tool.model.ToolStatus;
import com.ai.agent.tool.model.ToolTerminal;
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
    private static final DefaultRedisScript<Long> INGEST_SCRIPT =
            RedisLuaScripts.load("redis/tool/ingest.lua", Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCHEDULE_SCRIPT =
            RedisLuaScripts.load("redis/tool/schedule.lua", List.class);
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT =
            RedisLuaScripts.load("redis/tool/complete.lua", Long.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> REAP_EXPIRED_LEASES_SCRIPT =
            RedisLuaScripts.load("redis/tool/reap-expired-leases.lua", List.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CANCEL_WAITING_SCRIPT =
            RedisLuaScripts.load("redis/tool/cancel-waiting.lua", List.class);
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> CANCEL_ACTIVE_SCRIPT =
            RedisLuaScripts.load("redis/tool/cancel-active.lua", List.class);

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
                INGEST_SCRIPT,
                List.of(keys.queue(runId), keys.tools(runId), keys.toolUseIds(runId), keys.meta(runId)),
                call.toolUseId(),
                call.toolCallId(),
                Long.toString(call.seq()),
                toJson(state),
                "{\"type\":\"run_aborted\",\"message\":\"tool call ingested after run abort\"}",
                "{\"type\":\"interrupted\",\"message\":\"tool call ingested after run interrupt\"}"
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
                SCHEDULE_SCRIPT,
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
                COMPLETE_SCRIPT,
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
                REAP_EXPIRED_LEASES_SCRIPT,
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
                CANCEL_WAITING_SCRIPT,
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
    public void removeActiveRun(String runId) {
        redisTemplate.opsForSet().remove(keys.activeRuns(), runId);
    }

    @Override
    public List<ToolTerminal> abort(String runId, String reason) {
        redisTemplate.opsForHash().put(keys.meta(runId), "abort_requested", reason == null ? "true" : reason);
        List<String> terminals = redisTemplate.execute(
                CANCEL_ACTIVE_SCRIPT,
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
    public List<ToolTerminal> interrupt(String runId, String reason) {
        redisTemplate.opsForHash().put(keys.meta(runId), "interrupt_requested", reason == null ? "true" : reason);
        redisTemplate.opsForHash().put(keys.meta(runId), "interrupt_at", Long.toString(Instant.now().toEpochMilli()));
        List<String> terminals = redisTemplate.execute(
                CANCEL_ACTIVE_SCRIPT,
                List.of(keys.queue(runId), keys.tools(runId), keys.leases(runId)),
                CancelReason.INTERRUPTED.name(),
                "{\"type\":\"interrupted\",\"reason\":\"" + (reason == null ? "interrupt_requested" : reason) + "\"}"
        );
        if (terminals == null) {
            return List.of();
        }
        return terminals.stream().map(this::readTerminal).toList();
    }

    @Override
    public boolean abortRequested(String runId) {
        return redisTemplate.opsForHash().get(keys.meta(runId), "abort_requested") != null
                || interruptRequested(runId);
    }

    @Override
    public boolean interruptRequested(String runId) {
        return redisTemplate.opsForHash().get(keys.meta(runId), "interrupt_requested") != null;
    }

    @Override
    public void clearInterrupt(String runId) {
        redisTemplate.opsForHash().delete(keys.meta(runId), "interrupt_requested", "interrupt_at");
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
