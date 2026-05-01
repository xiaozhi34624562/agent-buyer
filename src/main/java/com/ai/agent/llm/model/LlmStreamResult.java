package com.ai.agent.llm.model;

import com.ai.agent.domain.FinishReason;

import java.util.List;

public record LlmStreamResult(
        String content,
        List<ToolCallMessage> toolCalls,
        FinishReason finishReason,
        LlmUsage usage,
        String rawDiagnosticJson
) {
}
