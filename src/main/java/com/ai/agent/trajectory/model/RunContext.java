package com.ai.agent.trajectory.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 运行上下文记录。
 * <p>
 * 包含运行配置信息，包括模型、提供商、工具限制等。
 * </p>
 *
 * @param runId               运行标识
 * @param effectiveAllowedTools 有效工具列表
 * @param model               模型名称
 * @param primaryProvider     主提供商
 * @param fallbackProvider    备用提供商
 * @param providerOptions     提供商选项
 * @param maxTurns            最大轮次
 * @param createdAt           创建时间
 * @param updatedAt           更新时间
 */
public record RunContext(
        String runId,
        List<String> effectiveAllowedTools,
        String model,
        String primaryProvider,
        String fallbackProvider,
        String providerOptions,
        int maxTurns,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public RunContext {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(primaryProvider, "primaryProvider must not be null");
        Objects.requireNonNull(fallbackProvider, "fallbackProvider must not be null");
        Objects.requireNonNull(providerOptions, "providerOptions must not be null");
        effectiveAllowedTools = effectiveAllowedTools == null
                ? List.of()
                : List.copyOf(effectiveAllowedTools);
    }
}
