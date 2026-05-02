package com.ai.agent.web.dto;

import com.ai.agent.domain.RunStatus;

/**
 * Agent 运行结果。
 *
 * <p>封装 Agent 运行完成后的结果数据，包括运行 ID、最终状态和输出文本。
 *
 * @param runId     运行实例 ID
 * @param status    运行状态
 * @param finalText 最终输出的文本内容
 * @author AI Agent
 */
public record AgentRunResult(
        String runId,
        RunStatus status,
        String finalText
) {
}
