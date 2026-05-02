package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

/**
 * 工具调用轨迹 DTO 记录。
 * <p>
 * 包含工具调用的详细信息，用于轨迹查询返回。
 * </p>
 *
 * @param toolCallId         工具调用标识
 * @param messageId          所属消息标识
 * @param seq                序号
 * @param toolUseId          工具使用标识
 * @param toolName           工具名称
 * @param concurrent         是否并发执行
 * @param idempotent         是否幂等
 * @param precheckFailed     预检是否失败
 * @param precheckErrorPreview 预检错误摘要
 * @param createdAt          创建时间
 */
public record ToolCallDto(
        String toolCallId,
        String messageId,
        Long seq,
        String toolUseId,
        String toolName,
        Boolean concurrent,
        Boolean idempotent,
        Boolean precheckFailed,
        String precheckErrorPreview,
        LocalDateTime createdAt
) {
}
