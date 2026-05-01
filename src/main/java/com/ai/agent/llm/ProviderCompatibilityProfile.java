package com.ai.agent.llm;

import com.ai.agent.tool.ToolSchema;

import java.util.List;
import java.util.Map;

public interface ProviderCompatibilityProfile {
    List<Map<String, Object>> toProviderTools(List<ToolSchema> schemas);
}
