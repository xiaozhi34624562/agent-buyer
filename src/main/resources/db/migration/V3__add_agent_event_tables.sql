CREATE TABLE IF NOT EXISTS agent_event (
    event_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_agent_event_run (run_id, created_at)
);

CREATE TABLE IF NOT EXISTS agent_tool_progress (
    progress_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(64) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    message VARCHAR(255) NULL,
    percent INT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_agent_tool_progress_run (run_id, created_at),
    KEY idx_agent_tool_progress_call (tool_call_id, created_at)
);
