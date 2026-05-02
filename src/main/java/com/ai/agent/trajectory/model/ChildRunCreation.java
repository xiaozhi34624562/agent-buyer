package com.ai.agent.trajectory.model;

/**
 * 子运行创建结果记录。
 * <p>
 * 表示子运行创建操作的结果，包含子运行标识和是否为新建。
 * </p>
 *
 * @param childRunId 子运行标识
 * @param created    是否为新建（false 表示复用了已有子运行）
 */
public record ChildRunCreation(
        String childRunId,
        boolean created
) {
}
