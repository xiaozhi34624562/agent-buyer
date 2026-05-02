package com.ai.agent.llm.provider;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * LLM提供者适配器注册表。
 * 管理所有可用的LLM提供者适配器，提供按名称解析功能。
 */
@Component
public final class LlmProviderAdapterRegistry {
    private final Map<String, LlmProviderAdapter> adaptersByName;

    /**
     * 构造注册表，自动索引所有适配器。
     *
     * @param adapters 所有可用的适配器列表
     * @throws IllegalStateException 如果发现重复的提供者名称
     */
    public LlmProviderAdapterRegistry(List<LlmProviderAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters must not be null");
        Map<String, LlmProviderAdapter> indexed = new LinkedHashMap<>();
        for (LlmProviderAdapter adapter : adapters) {
            Objects.requireNonNull(adapter, "adapter must not be null");
            String name = normalize(adapter.providerName());
            if (indexed.putIfAbsent(name, adapter) != null) {
                throw new IllegalStateException("duplicate llm provider: " + name);
            }
        }
        this.adaptersByName = Map.copyOf(indexed);
    }

    /**
     * 根据名称解析提供者适配器。
     *
     * @param providerName 提供者名称
     * @return 对应的适配器
     * @throws IllegalArgumentException 如果提供者名称未知或无效
     */
    public LlmProviderAdapter resolve(String providerName) {
        String name = normalize(providerName);
        LlmProviderAdapter adapter = adaptersByName.get(name);
        if (adapter == null) {
            throw new IllegalArgumentException("unknown llm provider: " + name);
        }
        return adapter;
    }

    private static String normalize(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("llm provider name must not be blank");
        }
        if (!providerName.equals(providerName.strip())) {
            throw new IllegalArgumentException("llm provider name must not contain surrounding whitespace");
        }
        return providerName.toLowerCase(Locale.ROOT);
    }
}
