package com.ai.agent.trajectory;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;

import java.util.List;
import java.util.Map;

public interface TrajectoryStore {
    void createRun(String runId, String userId);

    void updateRunStatus(String runId, RunStatus status, String error);

    int nextTurn(String runId);

    int currentTurn(String runId);

    String findRunUserId(String runId);

    RunStatus findRunStatus(String runId);

    String appendMessage(String runId, LlmMessage message);

    List<LlmMessage> loadMessages(String runId);

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

    List<ToolCall> findToolCallsByRun(String runId);

    Map<String, Object> loadTrajectory(String runId);
}
