package com.ai.agent.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 继续运行请求参数。
 *
 * <p>用于向处于等待状态的 Agent 运行实例发送新的用户消息以继续对话。
 *
 * @param message 用户消息，不能为空
 * @author AI Agent
 */
public record ContinueRunRequest(
        @NotNull @Valid UserMessage message
) {
}
