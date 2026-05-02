package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

/**
 * 工具执行进度数据传输对象。
 * <p>
 * 用于展示工具执行过程中的阶段性进度信息。
 * </p>
 */
public record ToolProgressDto(
        /** 进度标识 */
        String progressId,
        /** 工具调用标识 */
        String toolCallId,
        /** 执行阶段 */
        String stage,
        /** 进度消息预览 */
        String messagePreview,
        /** 进度百分比 */
        Integer percent,
        /** 创建时间 */
        LocalDateTime createdAt
) {
}
