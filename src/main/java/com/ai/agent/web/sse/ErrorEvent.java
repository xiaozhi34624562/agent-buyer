package com.ai.agent.web.sse;

import java.util.Map;

/**
 * 错误事件。
 *
 * <p>表示 Agent 运行过程中发生的错误，用于向客户端传递错误详情。
 *
 * @param runId   运行实例 ID
 * @param message 错误消息
 * @param code    错误码
 * @param details 错误详情信息
 * @author AI Agent
 */
public record ErrorEvent(
        String runId,
        String message,
        String code,
        Map<String, Object> details
) {

    /**
     * 紧凑构造器，确保 details 不为 null。
     */
    public ErrorEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * 创建简化版错误事件。
     *
     * @param runId   运行实例 ID
     * @param message 错误消息
     */
    public ErrorEvent(String runId, String message) {
        this(runId, message, null, Map.of());
    }
}
