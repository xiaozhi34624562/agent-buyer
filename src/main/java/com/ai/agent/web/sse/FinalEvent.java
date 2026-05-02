package com.ai.agent.web.sse;

import com.ai.agent.domain.RunStatus;

/**
 * 运行结束事件。
 *
 * <p>表示 Agent 运行完成时触发的事件，包含最终输出结果和状态信息。
 *
 * @param runId              运行实例 ID
 * @param finalText          最终输出文本
 * @param status             运行最终状态
 * @param nextActionRequired 下一步需要的操作提示
 * @author AI Agent
 */
public record FinalEvent(
        String runId,
        String finalText,
        RunStatus status,
        String nextActionRequired
) {
}
