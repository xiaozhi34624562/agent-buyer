package com.ai.agent.subagent.profile;

import com.ai.agent.subagent.model.SubAgentTask;
import java.util.List;

public interface SubAgentProfile {
    String agentType();

    List<String> allowedToolNames(List<String> parentAllowedToolNames);

    String renderSystemPrompt(SubAgentTask task);
}
