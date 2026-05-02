package com.ai.agent.tool.runtime;

import com.ai.agent.tool.model.ToolCall;

/**
 * 工具运行时接口，定义工具调用的入口方法。
 *
 * <p>此接口是工具运行时系统的核心入口，负责接收工具调用请求并触发执行流程。
 */
public interface ToolRuntime {
    /**
     * 处理工具调用请求。
     *
     * @param runId 运行标识符
     * @param call 工具调用请求
     */
    void onToolUse(String runId, ToolCall call);
}
