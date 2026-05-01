CREATE TABLE IF NOT EXISTS agent_run_context (
    run_id VARCHAR(64) PRIMARY KEY,
    effective_allowed_tools JSON NOT NULL,
    model VARCHAR(80) NOT NULL,
    max_turns INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_agent_run_context_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
);
