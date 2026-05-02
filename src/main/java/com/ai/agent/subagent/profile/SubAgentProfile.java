package com.ai.agent.subagent.profile;

import com.ai.agent.subagent.model.SubAgentTask;
import java.util.List;

/**
 * 子代理配置接口。
 * <p>
 * 定义子代理的类型标识、允许的工具集和系统提示词渲染能力。
 * </p>
 */
public interface SubAgentProfile {

    /**
     * 获取代理类型标识。
     *
     * @return 代理类型字符串
     */
    String agentType();

    /**
     * 根据父代理的允许工具集，计算子代理的允许工具集。
     *
     * @param parentAllowedToolNames 父代理允许的工具名称列表
     * @return 子代理允许的工具名称列表
     */
    List<String> allowedToolNames(List<String> parentAllowedToolNames);

    /**
     * 渲染子代理的系统提示词。
     *
     * @param task 子代理任务定义
     * @return 渲染后的系统提示词
     */
    String renderSystemPrompt(SubAgentTask task);
}
