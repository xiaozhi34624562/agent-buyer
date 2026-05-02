package com.ai.agent.web.sse;

/**
 * 文本增量事件。
 *
 * <p>表示 Agent 生成的文本内容增量片段，用于流式输出场景。
 *
 * @param runId     运行实例 ID
 * @param attemptId 尝试 ID
 * @param delta     文本增量内容
 * @author AI Agent
 */
public record TextDeltaEvent(String runId, String attemptId, String delta) {
}
