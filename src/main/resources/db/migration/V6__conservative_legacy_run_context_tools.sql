UPDATE agent_run_context context
JOIN agent_run run ON run.run_id = context.run_id
SET context.effective_allowed_tools = JSON_ARRAY()
WHERE run.status IN ('CREATED', 'RUNNING', 'WAITING_USER_CONFIRMATION')
  AND context.model = 'deepseek-reasoner'
  AND context.max_turns = 10
  AND JSON_LENGTH(context.effective_allowed_tools) = 2
  AND JSON_CONTAINS(context.effective_allowed_tools, JSON_QUOTE('query_order'), '$')
  AND JSON_CONTAINS(context.effective_allowed_tools, JSON_QUOTE('cancel_order'), '$');
