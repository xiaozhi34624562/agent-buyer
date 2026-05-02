package com.ai.agent.tool.model;

/**
 * 工具调用运行状态。
 * 记录工具调用在运行过程中的完整状态信息。
 *
 * @param call         工具调用信息
 * @param status       工具状态
 * @param attempt      尝试次数
 * @param leaseToken   租约令牌
 * @param leaseUntil   租约到期时间戳
 * @param workerId     执行者ID
 * @param cancelReason 取消原因
 * @param resultJson   结果JSON
 * @param errorJson    错误JSON
 */
public record ToolCallRuntimeState(
        ToolCall call,
        ToolStatus status,
        int attempt,
        String leaseToken,
        Long leaseUntil,
        String workerId,
        CancelReason cancelReason,
        String resultJson,
        String errorJson
) {
}
