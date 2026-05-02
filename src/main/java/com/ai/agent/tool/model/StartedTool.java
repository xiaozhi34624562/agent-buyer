package com.ai.agent.tool.model;

/**
 * 已启动的工具。
 * 表示工具调用已开始执行，持有租约信息。
 *
 * @param call       工具调用信息
 * @param attempt    尝试次数
 * @param leaseToken 租约令牌
 * @param leaseUntil 租约到期时间戳
 * @param workerId   执行者ID
 */
public record StartedTool(
        ToolCall call,
        int attempt,
        String leaseToken,
        long leaseUntil,
        String workerId
) {
}
