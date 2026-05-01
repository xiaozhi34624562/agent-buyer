package com.ai.agent.llm;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public final class LlmProviderAdapterRegistry {
    private final Map<String, LlmProviderAdapter> adaptersByName;

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
