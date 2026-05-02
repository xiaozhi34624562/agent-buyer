package com.ai.agent.todo.model;

/**
 * Todo 草稿记录。
 * <p>
 * 用于创建 Todo 时的初始数据，包含标题和备注。
 * </p>
 *
 * @param title 标题
 * @param notes 备注
 */
public record TodoDraft(
        String title,
        String notes
) {
}
