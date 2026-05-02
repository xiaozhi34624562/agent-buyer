package com.ai.agent.trajectory.port;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.trajectory.model.ChildRunCreation;
import java.util.List;

/**
 * 轨迹写入接口。
 * <p>
 * 提供轨迹数据的写入能力，包括运行创建、状态更新、消息追加等。
 * </p>
 */
public interface TrajectoryWriter {
    /**
     * 创建新运行。
     *
     * @param runId  运行标识
     * @param userId 用户标识
     */
    void createRun(String runId, String userId);

    /**
     * 创建子运行。
     * <p>
     * 默认实现抛出异常，需要具体存储类支持。
     * </p>
     *
     * @param runId             子运行标识
     * @param userId            用户标识
     * @param parentRunId       父运行标识
     * @param parentToolCallId  父工具调用标识
     * @param agentType         Agent 类型
     * @param parentLinkStatus  父链接状态
     * @return 创建结果
     */
    default ChildRunCreation createChildRun(
            String runId,
            String userId,
            String parentRunId,
            String parentToolCallId,
            String agentType,
            String parentLinkStatus
    ) {
        throw new UnsupportedOperationException("child run creation is not implemented");
    }

    /**
     * 更新父链接状态。
     *
     * @param childRunId       子运行标识
     * @param parentLinkStatus 父链接状态
     */
    default void updateParentLinkStatus(String childRunId, String parentLinkStatus) {
        throw new UnsupportedOperationException("parent link status update is not implemented");
    }

    /**
     * 更新运行状态。
     *
     * @param runId  运行标识
     * @param status 新状态
     * @param error  错误信息
     */
    void updateRunStatus(String runId, RunStatus status, String error);

    /**
     * 状态转换（CAS 操作）。
     *
     * @param runId     运行标识
     * @param expected 期望状态
     * @param next     新状态
     * @param error    错误信息
     * @return 是否转换成功
     */
    boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error);

    /**
     * 进入下一轮次。
     *
     * @param runId 运行标识
     * @return 新轮次号
     */
    int nextTurn(String runId);

    /**
     * 追加消息。
     *
     * @param runId  运行标识
     * @param message 消息
     * @return 消息标识
     */
    String appendMessage(String runId, LlmMessage message);

    /**
     * 记录 LLM 调用尝试。
     *
     * @param attemptId        调用标识
     * @param runId            运行标识
     * @param turnNo           轮次号
     * @param provider         提供商
     * @param model            模型
     * @param status           状态
     * @param finishReason     结束原因
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalTokens      总 token 数
     * @param errorJson        错误 JSON
     * @param rawDiagnosticJson 原始诊断 JSON
     */
    void writeLlmAttempt(
            String attemptId,
            String runId,
            int turnNo,
            String provider,
            String model,
            String status,
            FinishReason finishReason,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            String errorJson,
            String rawDiagnosticJson
    );

    /**
     * 记录工具调用。
     *
     * @param messageId 消息标识
     * @param call      工具调用
     */
    void writeToolCall(String messageId, ToolCall call);

    /**
     * 追加助手消息和工具调用。
     *
     * @param runId     运行标识
     * @param assistant 助手消息
     * @param toolCalls 工具调用列表
     * @return 消息标识
     */
    String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls);

    /**
     * 记录工具结果。
     *
     * @param runId     运行标识
     * @param toolUseId 工具使用标识
     * @param terminal  工具终端结果
     */
    void writeToolResult(String runId, String toolUseId, ToolTerminal terminal);

    /**
     * 记录 Agent 事件。
     *
     * @param runId        运行标识
     * @param eventType    事件类型
     * @param payloadJson 事件数据 JSON
     */
    default void writeAgentEvent(String runId, String eventType, String payloadJson) {
    }
}
