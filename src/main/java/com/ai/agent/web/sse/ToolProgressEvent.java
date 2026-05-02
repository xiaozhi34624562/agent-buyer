package com.ai.agent.web.sse;

/**
 * 工具执行进度事件。
 *
 * <p>表示工具执行过程中的进度更新，用于向客户端展示长时间运行工具的执行状态。
 *
 * @param runId      运行实例 ID
 * @param toolCallId 工具调用 ID
 * @param stage      当前执行阶段
 * @param message    进度消息
 * @param percent    完成百分比（0-100）
 * @author AI Agent
 */
public record ToolProgressEvent(String runId, String toolCallId, String stage, String message, Integer percent) {
}
