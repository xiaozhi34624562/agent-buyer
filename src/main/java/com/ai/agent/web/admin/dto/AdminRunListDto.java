package com.ai.agent.web.admin.dto;

import java.time.LocalDateTime;

/**
 * 管理后台运行列表数据传输对象。
 *
 * <p>表示运行列表中的单条记录，包含运行基本信息、上下文配置和时间戳等。
 *
 * @param runId            运行实例 ID
 * @param userId           用户 ID
 * @param status           运行状态
 * @param turnNo           当前对话轮次
 * @param agentType        Agent 类型
 * @param parentRunId      父运行 ID
 * @param parentLinkStatus 父运行关联状态
 * @param primaryProvider  主 LLM 提供商
 * @param fallbackProvider 备用 LLM 提供商
 * @param model            模型名称
 * @param maxTurns         最大轮次配置
 * @param startedAt        开始时间
 * @param updatedAt        更新时间
 * @param completedAt      完成时间
 * @param lastError        最后错误信息
 * @author AI Agent
 */
public record AdminRunListDto(
        String runId,
        String userId,
        String status,
        Integer turnNo,
        String agentType,
        String parentRunId,
        String parentLinkStatus,
        String primaryProvider,
        String fallbackProvider,
        String model,
        Integer maxTurns,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String lastError
) {
}