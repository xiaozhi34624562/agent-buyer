package com.ai.agent.subagent.model;

import java.util.List;

/**
 * 子代理任务定义。
 * <p>
 * 定义子代理执行所需的所有信息，包括任务内容、系统提示词和工具限制。
 * </p>
 *
 * @param parentRunId            父运行ID
 * @param parentToolCallId       父工具调用ID
 * @param userId                 用户ID
 * @param agentType              代理类型
 * @param task                   任务描述
 * @param systemPrompt           自定义系统提示词
 * @param effectiveAllowedTools  有效允许工具列表
 */
public record SubAgentTask(
        String parentRunId,
        String parentToolCallId,
        String userId,
        String agentType,
        String task,
        String systemPrompt,
        List<String> effectiveAllowedTools
) {
    public SubAgentTask {
        effectiveAllowedTools = effectiveAllowedTools == null ? List.of() : List.copyOf(effectiveAllowedTools);
    }
}
