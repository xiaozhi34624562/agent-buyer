package com.ai.agent.trajectory.dto;

/**
 * 消息中的工具调用数据传输对象。
 * <p>
 * 用于展示消息关联的工具调用摘要信息。
 * </p>
 */
public record MessageToolCallDto(
        /** 工具使用标识 */
        String toolUseId,
        /** 工具名称 */
        String toolName
) {
}
