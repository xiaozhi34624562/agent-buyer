package com.ai.agent.web.sse;

/**
 * 工具调用开始事件。
 *
 * <p>表示 Agent 开始调用工具时触发的事件，包含工具调用所需的全部信息。
 *
 * @param runId     运行实例 ID
 * @param toolUseId 工具调用 ID
 * @param toolName  工具名称
 * @param argsJson  工具参数 JSON 字符串
 * @author AI Agent
 */
public record ToolUseEvent(String runId, String toolUseId, String toolName, String argsJson) {
}
