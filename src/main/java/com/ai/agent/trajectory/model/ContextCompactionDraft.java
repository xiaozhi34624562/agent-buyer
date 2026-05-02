package com.ai.agent.trajectory.model;

import java.util.List;

/**
 * 上下文压缩草稿记录。
 * <p>
 * 用于创建压缩记录时的初始数据。
 * </p>
 *
 * @param strategy           压缩策略
 * @param beforeTokens       压缩前 token 数
 * @param afterTokens        压缩后 token 数
 * @param compactedMessageIds 已压缩消息标识列表
 */
public record ContextCompactionDraft(
        String strategy,
        Integer beforeTokens,
        Integer afterTokens,
        List<String> compactedMessageIds
) {
    public ContextCompactionDraft {
        compactedMessageIds = compactedMessageIds == null ? List.of() : List.copyOf(compactedMessageIds);
    }
}
