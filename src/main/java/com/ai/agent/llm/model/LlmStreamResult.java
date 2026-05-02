package com.ai.agent.llm.model;

import com.ai.agent.domain.FinishReason;

import java.util.List;

/**
 * LLM流式响应结果。
 * <p>
 * 封装LLM提供商返回的流式响应结果，包括生成内容、工具调用、结束原因、使用量和原始诊断信息。
 * </p>
 */
public record LlmStreamResult(
        String content,
        List<ToolCallMessage> toolCalls,
        FinishReason finishReason,
        LlmUsage usage,
        String rawDiagnosticJson
) {
}
