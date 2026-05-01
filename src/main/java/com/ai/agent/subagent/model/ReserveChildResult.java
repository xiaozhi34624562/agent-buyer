package com.ai.agent.subagent.model;

import com.ai.agent.subagent.runtime.SubAgentBudgetPolicy;

public record ReserveChildResult(
        boolean accepted,
        String parentRunId,
        String childRunId,
        String errorCode,
        ChildReserveRejectReason reason,
        boolean reused
) {
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
