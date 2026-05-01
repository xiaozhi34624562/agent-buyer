ALTER TABLE agent_run
    ADD COLUMN parent_run_id VARCHAR(64) NULL,
    ADD COLUMN parent_tool_call_id VARCHAR(64) NULL,
    ADD COLUMN agent_type VARCHAR(64) NOT NULL DEFAULT 'MAIN',
    ADD COLUMN parent_link_status VARCHAR(32) NULL;

CREATE INDEX idx_agent_run_parent
    ON agent_run(parent_run_id, status);

CREATE INDEX idx_agent_run_parent_link
    ON agent_run(parent_run_id, parent_link_status, status);

CREATE INDEX idx_agent_run_parent_tool
    ON agent_run(parent_tool_call_id);
