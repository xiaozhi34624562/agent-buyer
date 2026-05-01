package com.ai.agent.trajectory;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;

import java.util.List;

public interface TrajectoryWriter {
    void createRun(String runId, String userId);

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

    default void updateParentLinkStatus(String childRunId, String parentLinkStatus) {
        throw new UnsupportedOperationException("parent link status update is not implemented");
    }

    void updateRunStatus(String runId, RunStatus status, String error);

    boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error);

    int nextTurn(String runId);

    String appendMessage(String runId, LlmMessage message);

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

    void writeToolCall(String messageId, ToolCall call);

    String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls);

    void writeToolResult(String runId, String toolUseId, ToolTerminal terminal);

    default void writeAgentEvent(String runId, String eventType, String payloadJson) {
    }
}
