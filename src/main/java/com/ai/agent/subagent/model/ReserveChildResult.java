package com.ai.agent.subagent.model;

import com.ai.agent.subagent.runtime.SubAgentBudgetPolicy;

/**
 * 子运行预留结果。
 * <p>
 * 表示子运行资源预留的结果，包含是否接受、子运行ID、拒绝原因等信息。
 * </p>
 *
 * @param accepted    是否被接受
 * @param parentRunId 父运行ID
 * @param childRunId  子运行ID
 * @param errorCode   错误码
 * @param reason      拒绝原因
 * @param reused      是否复用了已有子运行
 */
public record ReserveChildResult(
        boolean accepted,
        String parentRunId,
        String childRunId,
        String errorCode,
        ChildReserveRejectReason reason,
        boolean reused
) {

    /**
     * 创建接受的预留结果。
     *
     * @param parentRunId 父运行ID
     * @param childRunId  子运行ID
     * @return 接受的预留结果
     */
    public static ReserveChildResult accepted(String parentRunId, String childRunId) {
        return accepted(parentRunId, childRunId, false);
    }

    public static ReserveChildResult accepted(String parentRunId, String childRunId, boolean reused) {
        return new ReserveChildResult(true, parentRunId, childRunId, null, null, reused);
    }

    public static ReserveChildResult rejected(
            String parentRunId,
            String childRunId,
            ChildReserveRejectReason reason
    ) {
        return new ReserveChildResult(
                false,
                parentRunId,
                childRunId,
                SubAgentBudgetPolicy.SUBAGENT_BUDGET_EXCEEDED,
                reason,
                false
        );
    }
}
