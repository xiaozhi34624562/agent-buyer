package com.ai.agent.todo.runtime;

import com.ai.agent.todo.model.TodoDraft;
import com.ai.agent.todo.model.TodoStatus;
import com.ai.agent.todo.model.TodoStep;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

@Repository
public class RedisTodoStore implements TodoStore {
    private static final String REPLACE_PLAN_SCRIPT = """
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            return #ARGV / 2
            """;

    private final TodoRedisKeys keys;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisTodoStore(TodoRedisKeys keys, StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.keys = keys;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<TodoStep> replacePlan(String runId, List<TodoDraft> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        Instant now = Instant.now();
        List<TodoStep> steps = java.util.stream.IntStream.range(0, items.size())
                .mapToObj(index -> newStep(index, items.get(index), now))
                .toList();
        Map<String, String> values = new LinkedHashMap<>();
        for (TodoStep step : steps) {
            values.put(step.stepId(), toJson(step));
        }
        replaceHash(keys.todos(runId), values);
        return steps;
    }

    @Override
    public TodoStep updateStep(String runId, String stepId, TodoStatus status, String notes) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        String key = keys.todos(runId);
        Object raw = redisTemplate.opsForHash().get(key, stepId);
        if (raw == null) {
            throw new IllegalArgumentException("todo step not found: " + stepId);
        }
        TodoStep current = readStep(raw.toString());
        TodoStep updated = new TodoStep(
                current.stepId(),
                current.title(),
                status,
                notes == null ? current.notes() : notes,
                Instant.now()
        );
        redisTemplate.opsForHash().put(key, stepId, toJson(updated));
        return updated;
    }

    @Override
    public List<TodoStep> listSteps(String runId) {
        List<Object> values = redisTemplate.opsForHash().values(keys.todos(runId));
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(Object::toString)
                .map(this::readStep)
                .sorted(Comparator.comparingInt(this::stepIndex).thenComparing(TodoStep::stepId))
                .toList();
    }

    @Override
    public List<TodoStep> findOpenTodos(String runId) {
        return listSteps(runId).stream()
                .filter(step -> step.status().open())
                .toList();
    }

    @Override
    public void recordReminder(String runId, int turnNo, List<TodoStep> openSteps) {
        redisTemplate.opsForHash().putAll(keys.reminder(runId), Map.of(
                "turnNo", Integer.toString(turnNo),
                "steps", toJson(openSteps == null ? List.of() : openSteps),
                "updatedAt", Instant.now().toString()
        ));
    }

    private TodoStep newStep(int zeroBasedIndex, TodoDraft draft, Instant now) {
        String title = draft == null ? null : draft.title();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("item title is required");
        }
        return new TodoStep(
                "step_" + (zeroBasedIndex + 1),
                title.trim(),
                TodoStatus.PENDING,
                blankToNull(draft.notes()),
                now
        );
    }

    private void replaceHash(String key, Map<String, String> values) {
        List<String> args = values.entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
                .toList();
        redisTemplate.execute(
                new DefaultRedisScript<>(REPLACE_PLAN_SCRIPT, Long.class),
                List.of(key),
                args.toArray(Object[]::new)
        );
    }

    private int stepIndex(TodoStep step) {
        String id = step.stepId();
        if (id != null && id.startsWith("step_")) {
            try {
                return Integer.parseInt(id.substring("step_".length()));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private TodoStep readStep(String json) {
        try {
            return objectMapper.readValue(json, TodoStep.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid redis todo step", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize todo value", e);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
