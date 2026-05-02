package com.ai.agent.web.sse;

import com.ai.agent.tool.model.ToolStatus;

/**
 * 工具执行结果事件。
 *
 * <p>表示工具执行完成后返回的结果，包含成功结果或错误信息。
 *
 * @param runId      运行实例 ID
 * @param toolUseId  工具调用 ID
 * @param status     工具执行状态
 * @param resultJson 执行结果 JSON 字符串，成功时有效
 * @param errorJson  错误信息 JSON 字符串，失败时有效
 * @author AI Agent
 */
public record ToolResultEvent(
        String runId,
        String toolUseId,
        ToolStatus status,
        String resultJson,
        String errorJson
) {
}
