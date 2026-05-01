package com.ai.agent.tool;

public enum CancelReason {
    USER_ABORT,
    RUN_ABORTED,
    RUN_TIMEOUT,
    TOOL_TIMEOUT,
    PRECHECK_FAILED,
    EXECUTOR_REJECTED,
    LEASE_EXPIRED
}
