package com.ai.agent.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Set;

/**
 * Agent 运行请求参数。
 *
 * <p>封装启动 Agent 运行所需的全部参数，包括对话消息列表、允许使用的工具集合以及 LLM 参数配置。
 *
 * @param messages         用户消息列表，不能为空
 * @param allowedToolNames 允许使用的工具名称集合，为空表示使用默认工具集
 * @param llmParams        LLM 参数配置，可选
 * @author AI Agent
 */
public record AgentRunRequest(
        @NotEmpty List<@Valid UserMessage> messages,
        Set<String> allowedToolNames,
        LlmParams llmParams
) {
}
