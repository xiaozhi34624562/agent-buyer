package com.ai.agent.todo;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TodoReminderInjectorTest {
    private final TodoStore store = mock(TodoStore.class);
    private final TodoReminderInjector injector = new TodoReminderInjector(new AgentProperties(), store);

    @Test
    void skipsTurnsThatAreNotReminderInterval() {
        List<LlmMessage> messages = List.of(LlmMessage.user("msg-1", "继续"));

        List<LlmMessage> result = injector.inject("run-1", 5, messages);

        assertThat(result).isSameAs(messages);
        verifyNoInteractions(store);
    }

    @Test
    void injectsTransientUserReminderForOpenTodosAndRecordsReminderStepSnapshot() {
        List<TodoStep> openSteps = List.of(
                new TodoStep("step_1", "查询昨天订单", TodoStatus.PENDING, null, null),
                new TodoStep("step_2", "取消订单", TodoStatus.IN_PROGRESS, "等待确认", null)
        );
        when(store.findOpenTodos("run-1")).thenReturn(openSteps);
        List<LlmMessage> messages = List.of(LlmMessage.user("msg-1", "继续"));

        List<LlmMessage> result = injector.inject("run-1", 6, messages);

        assertThat(result).hasSize(2);
        LlmMessage reminder = result.get(1);
        assertThat(reminder.role()).isEqualTo(MessageRole.USER);
        assertThat(reminder.content()).contains("step_1", "查询昨天订单", "IN_PROGRESS");
        assertThat(reminder.extras()).containsEntry("todoReminder", true);
        verify(store).recordReminder("run-1", 6, openSteps);
    }
}
