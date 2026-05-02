package com.ai.agent.llm.provider.deepseek;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.provider.AbstractOpenAiCompatibleProviderAdapter;
import com.ai.agent.llm.provider.OpenAiCompatibilityProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public final class DeepSeekProviderAdapter extends AbstractOpenAiCompatibleProviderAdapter {
    private static final String PROVIDER_NAME = "deepseek";
    private static final String DISPLAY_NAME = "DeepSeek";

    private final AgentProperties properties;

    public DeepSeekProviderAdapter(
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
        return properties.getLlm().getDeepseek().getApiKey();
    }

    @Override
    protected String getBaseUrl() {
        return properties.getLlm().getDeepseek().getBaseUrl();
    }

    @Override
    protected Duration getRequestTimeout() {
        return properties.getLlm().getDeepseek().getRequestTimeout();
    }

    @Override
    protected String getDefaultModel() {
        return properties.getLlm().getDeepseek().getDefaultModel();
    }

    @Override
    protected String getProviderDisplayName() {
        return DISPLAY_NAME;
    }
}