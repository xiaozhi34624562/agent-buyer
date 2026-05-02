package com.ai.agent.llm.provider;

import com.ai.agent.tool.core.ToolSchema;

import java.util.List;
import java.util.Map;

/**
 * 提供者兼容性配置接口。
 * 定义不同LLM提供者对工具Schema的转换规则，用于适配各提供者的特定格式要求。
 */
public interface ProviderCompatibilityProfile {
    /**
     * 将通用工具Schema转换为提供者特定的格式。
     *
     * @param schemas 工具Schema列表
     * @return 提供者格式的工具定义列表
     */
    List<Map<String, Object>> toProviderTools(List<ToolSchema> schemas);
}
