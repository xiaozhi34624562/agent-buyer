package com.ai.agent.todo.runtime;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.todo.model.TodoDraft;
import com.ai.agent.todo.model.TodoStatus;
import com.ai.agent.todo.model.TodoStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TodoStoreTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentProperties properties = new AgentProperties();
    private final TodoRedisKeys keys = new TodoRedisKeys(properties);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("rawtypes")
    private final HashOperations hashOps = mock(HashOperations.class);
    private final RedisTodoStore store = new RedisTodoStore(keys, redisTemplate, objectMapper);

    @Test
    void replacePlanStoresStepsInRunScopedHashTagKey() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        List<TodoStep> steps = store.replacePlan("run-1", List.of(
                new TodoDraft("查询昨天订单", null),
                new TodoDraft("确认可取消状态", "需要订单号")
        ));

        assertThat(keys.todos("run-1")).isEqualTo("agent:{run:run-1}:todos");
        assertThat(steps).extracting(TodoStep::stepId).containsExactly("step_1", "step_2");
        assertThat(steps).extracting(TodoStep::status)
                .containsExactly(TodoStatus.PENDING, TodoStatus.PENDING);
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("agent:{run:run-1}:todos")), any(), any(), any(), any());
    }

    @Test
    void updateStepPreservesExistingTitleAndChangesStatusNotes() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        TodoStep existing = new TodoStep("step_1", "查询昨天订单", TodoStatus.PENDING, null, null);
        when(hashOps.get("agent:{run:run-1}:todos", "step_1"))
                .thenReturn(objectMapper.writeValueAsString(existing));

        TodoStep updated = store.updateStep("run-1", "step_1", TodoStatus.IN_PROGRESS, "开始查询");

        assertThat(updated.title()).isEqualTo("查询昨天订单");
        assertThat(updated.status()).isEqualTo(TodoStatus.IN_PROGRESS);
        assertThat(updated.notes()).isEqualTo("开始查询");
        verify(hashOps).put(eq("agent:{run:run-1}:todos"), eq("step_1"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void findOpenTodosExcludesDoneAndCancelledSteps() throws Exception {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.values("agent:{run:run-1}:todos")).thenReturn(List.of(
                objectMapper.writeValueAsString(new TodoStep("step_2", "完成项", TodoStatus.DONE, null, null)),
                objectMapper.writeValueAsString(new TodoStep("step_1", "待处理", TodoStatus.PENDING, null, null)),
                objectMapper.writeValueAsString(new TodoStep("step_3", "取消项", TodoStatus.CANCELLED, null, null))
        ));

        List<TodoStep> open = store.findOpenTodos("run-1");

        assertThat(open).extracting(TodoStep::stepId).containsExactly("step_1");
    }

    @Test
    void recordReminderStoresTurnAndOpenStepsOnReminderKey() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        List<TodoStep> steps = List.of(new TodoStep("step_1", "待处理", TodoStatus.PENDING, null, null));

        store.recordReminder("run-1", 6, steps);

        assertThat(keys.reminder("run-1")).isEqualTo("agent:{run:run-1}:todo-reminder");
        verify(hashOps).putAll(eq("agent:{run:run-1}:todo-reminder"), anyMap());
    }
}
