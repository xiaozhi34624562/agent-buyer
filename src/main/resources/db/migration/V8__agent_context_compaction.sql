CREATE TABLE IF NOT EXISTS agent_context_compaction (
    compaction_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    strategy VARCHAR(40) NOT NULL,
    before_tokens INT NOT NULL,
    after_tokens INT NOT NULL,
    compacted_message_ids JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_agent_context_compaction_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    KEY idx_agent_context_compaction_run (run_id, created_at)
);
