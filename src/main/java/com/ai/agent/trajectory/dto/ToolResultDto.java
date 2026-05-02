package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

/**
 * 工具结果轨迹 DTO 记录。
 * <p>
 * 包含工具执行结果信息，用于轨迹查询返回。
 * </p>
 *
 * @param resultId     结果标识
 * @param toolCallId   工具调用标识
 * @param toolUseId    工具使用标识
 * @param status       执行状态
 * @param synthetic    是否合成结果
 * @param cancelReason 取消原因
 * @param preview      结果摘要
 * @param createdAt    创建时间
 */
public record ToolResultDto(
        String resultId,
        String toolCallId,
        String toolUseId,
        String status,
        Boolean synthetic,
        String cancelReason,
        String preview,
        LocalDateTime createdAt
) {
}
