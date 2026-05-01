CREATE INDEX idx_agent_llm_attempt_run_turn_started
    ON agent_llm_attempt (run_id, turn_no, started_at);

