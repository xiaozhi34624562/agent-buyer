package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上下文压缩轨迹 DTO 记录。
 * <p>
 * 包含消息压缩的详细信息，用于轨迹查询返回。
 * </p>
 *
 * @param compactionId       压缩标识
 * @param turnNo             轮次号
 * @param attemptId          调用标识
 * @param strategy           压缩策略
 * @param beforeTokens       压缩前 token 数
 * @param afterTokens        压缩后 token 数
 * @param compactedMessageIds 已压缩消息标识列表
 * @param createdAt          创建时间
 */
public record CompactionDto(
        String compactionId,
        Integer turnNo,
        String attemptId,
        String strategy,
        Integer beforeTokens,
        Integer afterTokens,
        List<String> compactedMessageIds,
        LocalDateTime createdAt
) {
    public CompactionDto {
        compactedMessageIds = compactedMessageIds == null ? List.of() : List.copyOf(compactedMessageIds);
    }
}
