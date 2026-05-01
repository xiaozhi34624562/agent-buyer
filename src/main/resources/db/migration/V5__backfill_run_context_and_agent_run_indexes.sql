INSERT INTO agent_run_context (run_id, effective_allowed_tools, model, max_turns)
SELECT run_id, JSON_ARRAY('query_order', 'cancel_order'), 'deepseek-reasoner', 10
FROM agent_run
WHERE run_id NOT IN (SELECT run_id FROM agent_run_context);

ALTER TABLE agent_run
    ADD INDEX idx_agent_run_status_updated (status, updated_at, run_id);
