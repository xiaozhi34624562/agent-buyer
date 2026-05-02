package com.ai.agent.llm.provider;

import com.ai.agent.trajectory.model.RunContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 提供者故障转移策略。
 * 根据运行上下文和失败类型，决定是否切换到备用LLM提供者。
 */
public final class ProviderFallbackPolicy {
    private final ObjectMapper objectMapper;

    public ProviderFallbackPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 选择故障转移的目标提供者。
     *
     * @param runContext 运行上下文
     * @param failure    导致故障转移的异常
     * @return 如果应切换到备用提供者则返回其名称，否则返回空
     */
    public Optional<String> selectFallbackProvider(RunContext runContext, Exception failure) {
        if (!isFallbackEnabled(runContext.providerOptions())) {
            return Optional.empty();
        }
        if (!(failure instanceof ProviderCallException providerFailure)
                || providerFailure.type() != ProviderErrorType.RETRYABLE_PRE_STREAM) {
            return Optional.empty();
        }
        String primary = trimToNull(runContext.primaryProvider());
        String fallback = trimToNull(runContext.fallbackProvider());
        if (primary == null || fallback == null || primary.equalsIgnoreCase(fallback)) {
            return Optional.empty();
        }
        return Optional.of(fallback);
    }

    private boolean isFallbackEnabled(String providerOptions) {
        if (providerOptions == null || providerOptions.isBlank()) {
            return true;
        }
        try {
            JsonNode root = objectMapper.readTree(providerOptions);
            JsonNode fallbackEnabled = root.get("fallbackEnabled");
            return fallbackEnabled == null || fallbackEnabled.isNull() || fallbackEnabled.asBoolean(true);
        } catch (Exception e) {
            return false;
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
