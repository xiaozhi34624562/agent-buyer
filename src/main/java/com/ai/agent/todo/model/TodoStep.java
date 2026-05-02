package com.ai.agent.todo.model;

import java.time.Instant;

/**
 * Todo 步骤记录。
 * <p>
 * 表示 Todo 列表中的一个具体步骤，包含标识、标题、状态、备注和更新时间。
 * </p>
 *
 * @param stepId   步骤标识
 * @param title    标题
 * @param status   状态
 * @param notes    备注
 * @param updatedAt 更新时间
 */
public record TodoStep(
        String stepId,
        String title,
        TodoStatus status,
        String notes,
        Instant updatedAt
) {
}
