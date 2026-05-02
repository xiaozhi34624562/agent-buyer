package com.ai.agent.tool.model;

/**
 * 工具使用请求。
 * 表示LLM请求使用工具的基本信息。
 *
 * @param toolUseId   工具使用ID
 * @param rawToolName 原始工具名称
 * @param argsJson    参数JSON
 */
public record ToolUse(
        String toolUseId,
        String rawToolName,
        String argsJson
) {
}
