package com.ai.agent.llm.provider.qwen;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.provider.AbstractOpenAiCompatibleProviderAdapter;
import com.ai.agent.llm.provider.OpenAiCompatibilityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Qwen提供者适配器。
 * 适配阿里云通义千问大语言模型，支持OpenAI兼容接口。
 */
@Component
public final class QwenProviderAdapter extends AbstractOpenAiCompatibleProviderAdapter {
    private static final String PROVIDER_NAME = "qwen";
    private static final String DISPLAY_NAME = "Qwen";

    private final AgentProperties properties;

    public QwenProviderAdapter(
            AgentProperties properties,
            OpenAiCompatibilityProfile compatibilityProfile,
            ObjectMapper objectMapper
    ) {
        super(objectMapper, compatibilityProfile);
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    protected String getApiKey() {
        return properties.getLlm().getQwen().getApiKey();
    }

    @Override
    protected String getBaseUrl() {
        return properties.getLlm().getQwen().getBaseUrl();
    }

    @Override
    protected Duration getRequestTimeout() {
        return properties.getLlm().getQwen().getRequestTimeout();
    }

    @Override
    protected String getDefaultModel() {
        return properties.getLlm().getQwen().getDefaultModel();
    }

    @Override
    protected String getProviderDisplayName() {
        return DISPLAY_NAME;
    }
}