package com.ai.agent.tool.core;

/**
 * 取消令牌接口，用于检查工具执行是否被请求取消。
 *
 * <p>在工具执行过程中，通过此接口检查取消状态，支持优雅终止长时间运行的任务。
 */
public interface CancellationToken {
    /**
     * 检查是否请求取消执行。
     *
     * @return 如果请求取消则返回true，否则返回false
     */
    boolean isCancellationRequested();
}
