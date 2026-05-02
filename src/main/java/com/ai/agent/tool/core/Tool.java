package com.ai.agent.tool.core;

import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;

/**
 * 工具接口，定义Agent可调用工具的核心契约。
 *
 * <p>所有Agent工具必须实现此接口，提供工具Schema定义、参数验证和执行逻辑。
 * 工具执行支持取消机制，可通过取消令牌优雅终止长时间运行的任务。
 */
public interface Tool {
    /**
     * 获取工具的Schema定义。
     *
     * @return 工具Schema，包含名称、描述、参数规范等元信息
     */
    ToolSchema schema();

    /**
     * 验证工具调用参数。
     *
     * <p>默认实现接受任意JSON参数，子类可覆盖以实现自定义验证逻辑。
     *
     * @param ctx 工具使用上下文，包含运行和用户信息
     * @param use 工具使用请求，包含调用参数
     * @return 验证结果，包含是否接受、规范化参数或错误信息
     */
    default ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        return ToolValidation.accepted(use.argsJson() == null || use.argsJson().isBlank() ? "{}" : use.argsJson());
    }

    /**
     * 执行工具逻辑。
     *
     * <p>子类必须覆盖此方法实现具体执行逻辑。默认实现抛出异常。
     *
     * @param ctx 工具执行上下文，包含运行信息、用户信息和事件推送接口
     * @param running 已启动的工具实例，包含调用信息和租约信息
     * @param token 取消令牌，用于检查执行是否被请求取消
     * @return 工具执行终态结果
     * @throws Exception 执行过程中发生的异常
     */
    default ToolTerminal run(ToolExecutionContext ctx, StartedTool running, CancellationToken token) throws Exception {
        throw new UnsupportedOperationException("tool run is not implemented: " + schema().name());
    }
}
