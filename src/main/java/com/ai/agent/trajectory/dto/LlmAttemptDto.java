package com.ai.agent.trajectory.dto;

import java.time.LocalDateTime;

/**
 * LLM 调用尝试轨迹 DTO 记录。
 * <p>
 * 包含 LLM 调用的详细信息，用于轨迹查询返回。
 * </p>
 *
 * @param attemptId       调用标识
 * @param turnNo          轮次号
 * @param provider        提供商
 * @param model           模型名称
 * @param status          状态
 * @param finishReason    结束原因
 * @param promptTokens    输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens     总 token 数
 * @param errorPreview    错误摘要
 * @param startedAt       开始时间
 * @param completedAt     完成时间
 */
public record LlmAttemptDto(
        String attemptId,
        Integer turnNo,
        String provider,
        String model,
        String status,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String errorPreview,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
