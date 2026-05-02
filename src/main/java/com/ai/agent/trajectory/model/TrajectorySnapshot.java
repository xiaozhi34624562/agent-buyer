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

/**
 * 轨迹快照记录。
 * <p>
 * 包含从数据库加载的完整轨迹实体数据，用于内部处理和 DTO 转换。
 * </p>
 *
 * @param run        运行实体
 * @param messages   消息实体列表
 * @param llmAttempts LLM 调用尝试实体列表
 * @param toolCalls  工具调用追踪实体列表
 * @param toolResults 工具结果追踪实体列表
 * @param events     事件实体列表
 * @param toolProgress 工具进度实体列表
 * @param compactions 压缩实体列表
 */
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

    /**
     * 复制列表或返回空列表。
     *
     * @param values 原始列表
     * @return 不可变副本或空列表
     */
    private static <T> List<T> copyOrEmpty(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
