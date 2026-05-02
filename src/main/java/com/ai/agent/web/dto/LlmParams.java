package com.ai.agent.web.dto;

/**
 * 大语言模型参数配置。
 *
 * <p>用于封装运行 Agent 时指定的 LLM 参数，包括模型名称、温度、最大 Token 数等。
 *
 * @param model       模型名称
 * @param temperature 温度参数，控制输出随机性
 * @param maxTokens   最大输出 Token 数
 * @param maxTurns    最大对话轮数
 * @author AI Agent
 */
public record LlmParams(
        String model,
        Double temperature,
        Integer maxTokens,
        Integer maxTurns
) {

    /**
     * 计算实际生效的最大对话轮数。
     *
     * @param configuredMaxTurns 系统配置的默认最大轮数
     * @return 实际生效的最大轮数，取请求参数与系统配置的较小值
     */
    public int effectiveMaxTurns(int configuredMaxTurns) {
        if (maxTurns == null) {
            return configuredMaxTurns;
        }
        return Math.min(maxTurns, configuredMaxTurns);
    }
}
