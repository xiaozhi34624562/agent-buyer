package com.ai.agent.subagent.runtime;

import com.ai.agent.subagent.model.SubAgentResult;
import com.ai.agent.subagent.model.SubAgentTask;
import com.ai.agent.tool.core.CancellationToken;

/**
 * 子代理运行器接口。
 * <p>
 * 定义子代理的执行契约，支持带取消令牌的同步执行模式。
 * </p>
 */
public interface SubAgentRunner {

    /**
     * 执行子代理任务。
     *
     * @param task  子代理任务定义，包含任务内容、父运行ID等信息
     * @param token 取消令牌，用于支持任务中断
     * @return 子代理执行结果
     * @throws Exception 执行过程中可能抛出的异常
     */
    SubAgentResult run(SubAgentTask task, CancellationToken token) throws Exception;
}
