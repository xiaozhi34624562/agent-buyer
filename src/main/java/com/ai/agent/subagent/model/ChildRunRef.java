package com.ai.agent.subagent.model;

/**
 * 子运行引用。
 * <p>
 * 用于追踪父子代理运行关系，包含子运行的关键状态信息。
 * </p>
 *
 * @param parentRunId        父运行ID
 * @param childRunId         子运行ID
 * @param parentToolCallId   父工具调用ID
 * @param agentType          代理类型
 * @param userTurnNo         用户轮次号
 * @param state              子运行状态
 * @param parentLinkStatus   父子链接状态
 * @param reservedAtEpochMs  预留时间戳（毫秒）
 * @param releasedAtEpochMs  释放时间戳（毫秒）
 * @param releaseReason      释放原因
 */
public record ChildRunRef(
        String parentRunId,
        String childRunId,
        String parentToolCallId,
        String agentType,
        int userTurnNo,
        ChildRunState state,
        ParentLinkStatus parentLinkStatus,
        long reservedAtEpochMs,
        Long releasedAtEpochMs,
        ChildReleaseReason releaseReason
) {
}
