package com.ai.agent.llm;

import com.ai.agent.tool.ToolSchema;

import java.util.List;

public record LlmChatRequest(
        String runId,
        String attemptId,
        String model,
        Double temperature,
        Integer maxTokens,
        List<LlmMessage> messages,
        List<ToolSchema> tools
) {
}
