package com.ai.agent.domain;

public enum RunStatus {
    CREATED,
    RUNNING,
    PAUSED,
    WAITING_USER_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    FAILED_RECOVERED,
    CANCELLED,
    TIMEOUT
}
