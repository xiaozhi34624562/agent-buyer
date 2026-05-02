package com.ai.agent.subagent.model;

import com.ai.agent.domain.RunStatus;

/**
 * 子代理执行结果。
 * <p>
 * 包含子代理执行的状态、摘要信息和部分结果标识。
 * </p>
 *
 * @param childRunId 子运行ID
 * @param status     运行状态
 * @param summary    结果摘要
 * @param partial    是否为部分结果
 */
public record SubAgentResult(
        String childRunId,
        RunStatus status,
        String summary,
        boolean partial
) {
}
