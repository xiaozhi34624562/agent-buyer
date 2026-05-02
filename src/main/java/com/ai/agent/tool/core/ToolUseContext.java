package com.ai.agent.tool.core;

/**
 * 工具使用上下文，包含工具验证阶段所需的运行信息。
 *
 * <p>此记录类封装了工具调用的基本上下文信息，在工具参数验证阶段使用。
 */
public record ToolUseContext(
        /** 运行标识符 */
        String runId,
        /** 用户标识符 */
        String userId
) {
}
