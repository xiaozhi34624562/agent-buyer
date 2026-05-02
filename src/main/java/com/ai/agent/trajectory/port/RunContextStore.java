package com.ai.agent.trajectory.port;

import com.ai.agent.trajectory.model.RunContext;

/**
 * 运行上下文存储接口。
 * <p>
 * 提供运行配置信息的持久化能力，包括模型、提供商、工具限制等。
 * </p>
 */
public interface RunContextStore {

    /**
     * 创建运行上下文。
     *
     * @param context 运行上下文
     */
    void create(RunContext context);

    /**
     * 加载运行上下文。
     *
     * @param runId 运行标识
     * @return 运行上下文
     */
    RunContext load(String runId);
}
