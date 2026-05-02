package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

/**
 * 事件轨迹 DTO 记录。
 * <p>
 * 包含 Agent 运行过程中记录的事件信息。
 * </p>
 *
 * @param eventId       事件标识
 * @param eventType     事件类型
 * @param payloadPreview 事件数据摘要
 * @param createdAt     创建时间
 */
public record EventDto(
        String eventId,
        String eventType,
        String payloadPreview,
        LocalDateTime createdAt
) {
}
