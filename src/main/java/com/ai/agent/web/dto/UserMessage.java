package com.ai.agent.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户消息数据结构。
 *
 * <p>表示对话中的单条消息，包含角色标识和消息内容。
 *
 * @param role    消息角色，如 user、assistant、system
 * @param content 消息内容
 * @author AI Agent
 */
public record UserMessage(
        @NotBlank String role,
        @NotBlank String content
) {
}
