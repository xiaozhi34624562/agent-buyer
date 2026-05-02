package com.ai.agent.tool.model;

/**
 * 工具调用。
 * 表示一个工具调用的完整信息，包括运行上下文和执行参数。
 *
 * @param runId          运行ID
 * @param toolCallId     工具调用ID
 * @param seq            序号
 * @param toolUseId      工具使用ID
 * @param rawToolName    原始工具名称
 * @param toolName       标准化工具名称
 * @param argsJson       参数JSON
 * @param isConcurrent   是否并发执行
 * @param idempotent     是否幂等
 * @param precheckFailed 预检查是否失败
 * @param precheckErrorJson 预检查错误JSON
 * @param timeoutMs      超时毫秒数
 */
public record ToolCall(
        String runId,
        String toolCallId,
        long seq,
        String toolUseId,
        String rawToolName,
        String toolName,
        String argsJson,
        boolean isConcurrent,
        boolean idempotent,
        boolean precheckFailed,
        String precheckErrorJson,
        Long timeoutMs
) {
    /**
     * 创建不带超时参数的工具调用。
     *
     * @param runId          运行ID
     * @param toolCallId     工具调用ID
     * @param seq            序号
     * @param toolUseId      工具使用ID
     * @param rawToolName    原始工具名称
     * @param toolName       标准化工具名称
     * @param argsJson       参数JSON
     * @param isConcurrent   是否并发执行
     * @param idempotent     是否幂等
     * @param precheckFailed 预检查是否失败
     * @param precheckErrorJson 预检查错误JSON
     */
    public ToolCall(
            String runId,
            String toolCallId,
            long seq,
            String toolUseId,
            String rawToolName,
            String toolName,
            String argsJson,
            boolean isConcurrent,
            boolean idempotent,
            boolean precheckFailed,
            String precheckErrorJson
    ) {
        this(
                runId,
                toolCallId,
                seq,
                toolUseId,
                rawToolName,
                toolName,
                argsJson,
                isConcurrent,
                idempotent,
                precheckFailed,
                precheckErrorJson,
                null
        );
    }
}
