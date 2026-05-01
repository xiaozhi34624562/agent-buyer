package com.ai.agent.todo.runtime;

import com.ai.agent.todo.model.TodoDraft;
import com.ai.agent.todo.model.TodoStatus;
import com.ai.agent.todo.model.TodoStep;
import java.util.List;

public interface TodoStore {
    List<TodoStep> replacePlan(String runId, List<TodoDraft> items);

    TodoStep updateStep(String runId, String stepId, TodoStatus status, String notes);

    List<TodoStep> listSteps(String runId);

    List<TodoStep> findOpenTodos(String runId);

    void recordReminder(String runId, int turnNo, List<TodoStep> openSteps);
}
