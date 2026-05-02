package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

/**
 * 运行轨迹 DTO 记录。
 * <p>
 * 包含运行的基本信息，用于轨迹查询返回。
 * </p>
 *
 * @param runId             运行标识
 * @param status            运行状态
 * @param turnNo            当前轮次号
 * @param parentRunId       父运行标识
 * @param parentToolCallId  父工具调用标识
 * @param agentType         Agent 类型
 * @param parentLinkStatus  父链接状态
 * @param startedAt         启动时间
 * @param updatedAt         更新时间
 * @param completedAt       完成时间
 * @param lastErrorPreview  最后错误摘要
 */
public record RunDto(
        String runId,
        String status,
        Integer turnNo,
        String parentRunId,
        String parentToolCallId,
        String agentType,
        String parentLinkStatus,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String lastErrorPreview
) {
}
