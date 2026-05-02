package com.ai.agent.subagent.model;

import java.time.Instant;

/**
 * 子运行预留命令。
 * <p>
 * 包含预留子运行资源所需的所有信息，用于向注册表申请子运行资源。
 * </p>
 *
 * @param parentRunId      父运行ID
 * @param childRunId       子运行ID
 * @param parentToolCallId 父工具调用ID
 * @param agentType        代理类型
 * @param userTurnNo       用户轮次号
 * @param requestedAt      请求时间戳
 */
public record ReserveChildCommand(
        String parentRunId,
        String childRunId,
        String parentToolCallId,
        String agentType,
        int userTurnNo,
        Instant requestedAt
) {
    public ReserveChildCommand {
        if (parentRunId == null || parentRunId.isBlank()) {
            throw new IllegalArgumentException("parentRunId is required");
        }
        if (childRunId == null || childRunId.isBlank()) {
            throw new IllegalArgumentException("childRunId is required");
        }
        if (parentToolCallId == null || parentToolCallId.isBlank()) {
            throw new IllegalArgumentException("parentToolCallId is required");
        }
        if (agentType == null || agentType.isBlank()) {
            throw new IllegalArgumentException("agentType is required");
        }
        if (userTurnNo <= 0) {
            throw new IllegalArgumentException("userTurnNo must be positive");
        }
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
        agentType = agentType.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
