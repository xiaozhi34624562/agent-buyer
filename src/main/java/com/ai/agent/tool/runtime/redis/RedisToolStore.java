package com.ai.agent.tool.runtime.redis;

import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis工具存储接口。
 * 定义工具调用状态的持久化操作，包括入队、调度、完成和取消等。
 */
public interface RedisToolStore {
    /**
     * 将工具调用入队等待执行。
     *
     * @param runId 运行ID
     * @param call  工具调用
     * @return 是否成功入队
     */
    boolean ingestWaiting(String runId, ToolCall call);

    /**
     * 调度等待中的工具调用。
     *
     * @param runId 运行ID
     * @return 已启动的工具列表
     */
    List<StartedTool> schedule(String runId);

    /**
     * 完成工具执行。
     *
     * @param running  已启动的工具
     * @param terminal 执行终态
     * @return 是否成功完成
     */
    boolean complete(StartedTool running, ToolTerminal terminal);

    /**
     * 清理过期租约。
     *
     * @param runId      运行ID
     * @param nowMillis  当前时间戳
     * @return 过期租约对应的终态列表
     */
    List<ToolTerminal> reapExpiredLeases(String runId, long nowMillis);

    /**
     * 取消等待中的工具调用。
     *
     * @param runId 运行ID
     * @param reason 取消原因
     * @return 取消的终态列表
     */
    List<ToolTerminal> cancelWaiting(String runId, CancelReason reason);

    /**
     * 获取工具执行终态。
     *
     * @param runId     运行ID
     * @param toolCallId 工具调用ID
     * @return 终态结果，如果未完成则返回空
     */
    Optional<ToolTerminal> terminal(String runId, String toolCallId);

    /**
     * 获取活跃运行ID集合。
     *
     * @return 活跃运行ID集合
     */
    Set<String> activeRunIds();

    /**
     * 移除活跃运行记录。
     *
     * @param runId 运行ID
     */
    default void removeActiveRun(String runId) {
    }

    /**
     * 中止运行并取消所有工具调用。
     *
     * @param runId 运行ID
     * @param reason 中止原因
     * @return 取消的终态列表
     */
    List<ToolTerminal> abort(String runId, String reason);

    /**
     * 中断运行并取消正在执行的工具。
     *
     * @param runId 运行ID
     * @param reason 中断原因
     * @return 取消的终态列表
     */
    default List<ToolTerminal> interrupt(String runId, String reason) {
        return List.of();
    }

    /**
     * 检查是否请求中止。
     *
     * @param runId 运行ID
     * @return 是否请求中止
     */
    boolean abortRequested(String runId);

    /**
     * 检查是否请求中断。
     *
     * @param runId 运行ID
     * @return 是否请求中断
     */
    default boolean interruptRequested(String runId) {
        return false;
    }

    /**
     * 清除中断标记。
     *
     * @param runId 运行ID
     */
    default void clearInterrupt(String runId) {
    }
}
