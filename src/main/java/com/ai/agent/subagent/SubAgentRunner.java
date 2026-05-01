package com.ai.agent.subagent;

import com.ai.agent.tool.CancellationToken;

public interface SubAgentRunner {
    SubAgentResult run(SubAgentTask task, CancellationToken token) throws Exception;
}
