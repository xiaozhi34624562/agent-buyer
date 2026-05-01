package com.ai.agent.trajectory.dto;

import java.util.List;

public record AgentRunTrajectoryDto(
        RunDto run,
        List<MessageDto> messages,
        List<LlmAttemptDto> llmAttempts,
        List<ToolCallDto> toolCalls,
        List<ToolResultDto> toolResults,
        List<EventDto> events,
        List<ToolProgressDto> toolProgress
) {
    public AgentRunTrajectoryDto {
        messages = copyOrEmpty(messages);
        llmAttempts = copyOrEmpty(llmAttempts);
        toolCalls = copyOrEmpty(toolCalls);
        toolResults = copyOrEmpty(toolResults);
        events = copyOrEmpty(events);
        toolProgress = copyOrEmpty(toolProgress);
    }

    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
