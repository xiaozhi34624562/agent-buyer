package com.ai.agent.trajectory.dto;

import java.util.List;

/**
 * Agent 运行轨迹完整 DTO 记录。
 * <p>
 * 包含运行的完整轨迹信息，包括消息、LLM 调用、工具调用、结果、事件、进度和压缩记录。
 * </p>
 *
 * @param run        运行信息
 * @param messages   消息列表
 * @param llmAttempts LLM 调用尝试列表
 * @param toolCalls  工具调用列表
 * @param toolResults 工具结果列表
 * @param events     事件列表
 * @param toolProgress 工具进度列表
 * @param compactions 压缩记录列表
 */
public record AgentRunTrajectoryDto(
        RunDto run,
        List<MessageDto> messages,
        List<LlmAttemptDto> llmAttempts,
        List<ToolCallDto> toolCalls,
        List<ToolResultDto> toolResults,
        List<EventDto> events,
        List<ToolProgressDto> toolProgress,
        List<CompactionDto> compactions
) {
    public AgentRunTrajectoryDto {
        messages = copyOrEmpty(messages);
        llmAttempts = copyOrEmpty(llmAttempts);
        toolCalls = copyOrEmpty(toolCalls);
        toolResults = copyOrEmpty(toolResults);
        events = copyOrEmpty(events);
        toolProgress = copyOrEmpty(toolProgress);
        compactions = copyOrEmpty(compactions);
    }

    /**
     * 复制列表或返回空列表。
     *
     * @param values 原始列表
     * @return 不可变副本或空列表
     */
    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
