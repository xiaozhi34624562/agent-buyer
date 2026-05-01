ALTER TABLE agent_tool_call_trace
    ADD COLUMN idempotent BOOLEAN NOT NULL DEFAULT FALSE AFTER is_concurrent;
