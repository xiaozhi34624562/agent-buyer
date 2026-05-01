package com.ai.agent.todo;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public final class TodoReminderInjector {
    private final AgentProperties properties;
    private final TodoStore store;

    public TodoReminderInjector(TodoStore store) {
        this(new AgentProperties(), store);
    }

    @Autowired
    public TodoReminderInjector(AgentProperties properties, TodoStore store) {
        this.properties = properties == null ? new AgentProperties() : properties;
        this.store = store;
    }

    public List<LlmMessage> inject(String runId, int turnNo, List<LlmMessage> messages) {
        int interval = Math.max(1, properties.getTodo().getReminderTurnInterval());
        if (turnNo <= 0 || turnNo % interval != 0) {
            return messages;
        }
        List<TodoStep> openSteps = store.findOpenTodos(runId);
        if (openSteps.isEmpty()) {
            return messages;
        }
        store.recordReminder(runId, turnNo, openSteps);
        List<LlmMessage> injected = new ArrayList<>(messages.size() + 1);
        injected.addAll(messages);
        injected.add(reminderMessage(runId, turnNo, openSteps));
        return List.copyOf(injected);
    }

    private LlmMessage reminderMessage(String runId, int turnNo, List<TodoStep> openSteps) {
        return new LlmMessage(
                "todo-reminder-" + runId + "-" + turnNo,
                MessageRole.USER,
                render(openSteps),
                List.of(),
                null,
                Map.of("todoReminder", true, "transient", true)
        );
    }

    private String render(List<TodoStep> openSteps) {
        StringBuilder builder = new StringBuilder("Open ToDo steps for this run. Use todo_write when a step changes status:\n");
        for (TodoStep step : openSteps) {
            builder.append("- ")
                    .append(step.stepId())
                    .append(" [")
                    .append(step.status().name())
                    .append("] ")
                    .append(step.title());
            if (step.notes() != null && !step.notes().isBlank()) {
                builder.append(" - ").append(step.notes());
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }
}
