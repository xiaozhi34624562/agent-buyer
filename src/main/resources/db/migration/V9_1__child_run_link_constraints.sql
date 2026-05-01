ALTER TABLE agent_run
    ADD UNIQUE KEY uk_agent_run_parent_tool (parent_tool_call_id);
