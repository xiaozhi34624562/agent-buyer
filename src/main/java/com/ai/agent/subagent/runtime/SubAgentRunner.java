package com.ai.agent.subagent.runtime;

import com.ai.agent.subagent.model.SubAgentResult;
import com.ai.agent.subagent.model.SubAgentTask;
import com.ai.agent.tool.core.CancellationToken;

public interface SubAgentRunner {
    SubAgentResult run(SubAgentTask task, CancellationToken token) throws Exception;
}
