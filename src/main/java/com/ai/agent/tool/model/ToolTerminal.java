package com.ai.agent.tool.model;

/**
 * 工具执行终态。
 * 表示工具执行完成后的最终状态和结果。
 *
 * @param toolCallId   工具调用ID
 * @param status       工具状态
 * @param resultJson   结果JSON
 * @param errorJson    错误JSON
 * @param cancelReason 取消原因
 * @param synthetic    是否为合成结果
 */
public record ToolTerminal(
        String toolCallId,
        ToolStatus status,
        String resultJson,
        String errorJson,
        CancelReason cancelReason,
        boolean synthetic
) {
    /**
     * 创建成功的终态结果。
     *
     * @param toolCallId 工具调用ID
     * @param resultJson 结果JSON
     * @return 成功终态
     */
    public static ToolTerminal succeeded(String toolCallId, String resultJson) {
        return new ToolTerminal(toolCallId, ToolStatus.SUCCEEDED, resultJson, null, null, false);
    }

    /**
     * 创建失败的终态结果。
     *
     * @param toolCallId 工具调用ID
     * @param errorJson  错误JSON
     * @return 失败终态
     */
    public static ToolTerminal failed(String toolCallId, String errorJson) {
        return new ToolTerminal(toolCallId, ToolStatus.FAILED, null, errorJson, null, false);
    }

    /**
     * 创建合成的取消终态结果。
     *
     * @param toolCallId 工具调用ID
     * @param reason     取消原因
     * @param errorJson  错误JSON
     * @return 合成取消终态
     */
    public static ToolTerminal syntheticCancelled(String toolCallId, CancelReason reason, String errorJson) {
        return new ToolTerminal(toolCallId, ToolStatus.CANCELLED, null, errorJson, reason, true);
    }
}
