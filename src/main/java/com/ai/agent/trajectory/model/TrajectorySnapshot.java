package com.ai.agent.trajectory.model;

import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.ai.agent.persistence.entity.AgentLlmAttemptEntity;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;

import java.util.List;

public record TrajectorySnapshot(
        AgentRunEntity run,
        List<AgentMessageEntity> messages,
        List<AgentLlmAttemptEntity> llmAttempts,
        List<AgentToolCallTraceEntity> toolCalls,
        List<AgentToolResultTraceEntity> toolResults,
        List<AgentEventEntity> events,
        List<AgentToolProgressEntity> toolProgress,
        List<AgentContextCompactionEntity> compactions
) {
    public TrajectorySnapshot {
        messages = copyOrEmpty(messages);
        llmAttempts = copyOrEmpty(llmAttempts);
        toolCalls = copyOrEmpty(toolCalls);
        toolResults = copyOrEmpty(toolResults);
        events = copyOrEmpty(events);
        toolProgress = copyOrEmpty(toolProgress);
        compactions = copyOrEmpty(compactions);
    }

    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
