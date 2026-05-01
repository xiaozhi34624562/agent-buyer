package com.ai.agent.llm.provider;

import com.ai.agent.tool.core.ToolSchema;

import java.util.List;
import java.util.Map;

public interface ProviderCompatibilityProfile {
    List<Map<String, Object>> toProviderTools(List<ToolSchema> schemas);
}
