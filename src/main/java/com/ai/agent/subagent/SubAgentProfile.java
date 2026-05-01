package com.ai.agent.subagent;

import java.util.List;

public interface SubAgentProfile {
    String agentType();

    List<String> allowedToolNames(List<String> parentAllowedToolNames);

    String renderSystemPrompt(SubAgentTask task);
}
