package com.ai.agent.domain;

public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_USER_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    FAILED_RECOVERED,
    CANCELLED,
    TIMEOUT
}
