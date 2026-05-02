package com.ai.agent.tool.core;

import com.ai.agent.web.sse.AgentEventSink;

/**
 * 工具执行上下文，包含工具运行阶段所需的完整信息。
 *
 * <p>此记录类封装了执行阶段的上下文信息，包括运行标识、用户标识和事件推送接口，
 * 用于工具执行过程中推送进度事件。
 */
public record ToolExecutionContext(
        /** 运行标识符 */
        String runId,
        /** 用户标识符 */
        String userId,
        /** Agent事件推送接口，用于推送工具执行进度事件 */
        AgentEventSink sink
) {
}
