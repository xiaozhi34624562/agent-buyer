package com.ai.agent.todo.runtime;

import com.ai.agent.todo.model.TodoDraft;
import com.ai.agent.todo.model.TodoStatus;
import com.ai.agent.todo.model.TodoStep;
import java.util.List;

/**
 * Todo 存储接口。
 * <p>
 * 提供 Todo 步骤的持久化操作，包括计划替换、步骤更新、列表查询和提醒记录。
 * </p>
 */
public interface TodoStore {
    /**
     * 替换 Todo 计划。
     *
     * @param runId 运行标识
     * @param items Todo 草稿列表
     * @return 创建的 Todo 步骤列表
     */
    List<TodoStep> replacePlan(String runId, List<TodoDraft> items);

    /**
     * 更新 Todo 步骤状态。
     *
     * @param runId  运行标识
     * @param stepId 步骤标识
     * @param status 新状态
     * @param notes  备注（可为 null）
     * @return 更新后的步骤
     */
    TodoStep updateStep(String runId, String stepId, TodoStatus status, String notes);

    /**
     * 列出所有 Todo 步骤。
     *
     * @param runId 运行标识
     * @return 步骤列表
     */
    List<TodoStep> listSteps(String runId);

    /**
     * 查找开放的 Todo 步骤。
     *
     * @param runId 运行标识
     * @return 未完成且未取消的步骤列表
     */
    List<TodoStep> findOpenTodos(String runId);

    /**
     * 记录提醒信息。
     *
     * @param runId      运行标识
     * @param turnNo     轮次号
     * @param openSteps  开放步骤列表
     */
    void recordReminder(String runId, int turnNo, List<TodoStep> openSteps);
}
