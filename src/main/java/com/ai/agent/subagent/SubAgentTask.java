package com.ai.agent.subagent;

import java.util.List;

public record SubAgentTask(
        String parentRunId,
        String parentToolCallId,
        String userId,
        String agentType,
        String task,
        String systemPrompt,
        List<String> effectiveAllowedTools
) {
    public SubAgentTask {
        effectiveAllowedTools = effectiveAllowedTools == null ? List.of() : List.copyOf(effectiveAllowedTools);
    }
}
