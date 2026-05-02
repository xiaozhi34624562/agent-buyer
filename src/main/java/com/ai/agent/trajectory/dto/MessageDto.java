package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 消息轨迹 DTO 记录。
 * <p>
 * 包含消息的基本信息和工具调用列表，用于轨迹查询返回。
 * </p>
 *
 * @param messageId      消息标识
 * @param seq            序号
 * @param role           角色（user/assistant/system/tool）
 * @param contentPreview 内容摘要
 * @param toolUseId      工具使用标识
 * @param toolCalls      工具调用列表
 * @param createdAt      创建时间
 */
public record MessageDto(
        String messageId,
        Long seq,
        String role,
        String contentPreview,
        String toolUseId,
        List<MessageToolCallDto> toolCalls,
        LocalDateTime createdAt
) {
    public MessageDto {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
