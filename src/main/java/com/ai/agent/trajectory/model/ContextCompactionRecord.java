package com.ai.agent.trajectory.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上下文压缩记录。
 * <p>
 * 持久化后的完整压缩记录，包含压缩标识和时间信息。
 * </p>
 *
 * @param compactionId       压缩标识
 * @param runId              运行标识
 * @param turnNo             轮次号
 * @param attemptId          调用标识
 * @param strategy           压缩策略
 * @param beforeTokens       压缩前 token 数
 * @param afterTokens        压缩后 token 数
 * @param compactedMessageIds 已压缩消息标识列表
 * @param createdAt          创建时间
 */
public record ContextCompactionRecord(
        String compactionId,
        String runId,
        Integer turnNo,
        String attemptId,
        String strategy,
        Integer beforeTokens,
        Integer afterTokens,
        List<String> compactedMessageIds,
        LocalDateTime createdAt
) {
    public ContextCompactionRecord {
        compactedMessageIds = compactedMessageIds == null ? List.of() : List.copyOf(compactedMessageIds);
    }
}
