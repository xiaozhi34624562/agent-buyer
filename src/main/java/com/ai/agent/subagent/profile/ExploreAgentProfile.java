package com.ai.agent.subagent.profile;

import com.ai.agent.subagent.model.SubAgentTask;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class ExploreAgentProfile implements SubAgentProfile {
    private static final Set<String> EXPLORE_TOOLS = Set.of("query_order", "skill_list", "skill_view");

    @Override
    public String agentType() {
        return "explore";
    }

    @Override
    public List<String> allowedToolNames(List<String> parentAllowedToolNames) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (String toolName : parentAllowedToolNames) {
            if (EXPLORE_TOOLS.contains(toolName)) {
                allowed.add(toolName);
            }
        }
        return List.copyOf(allowed);
    }

    @Override
    public String renderSystemPrompt(SubAgentTask task) {
        String taskSpecificPrompt = task.systemPrompt() == null || task.systemPrompt().isBlank()
                ? "No additional task-specific system prompt."
                : task.systemPrompt();
        return """
                You are ExploreAgent, a child agent for Agent Buyer.
                Your job is to investigate the delegated task with an isolated context and return a concise result summary.
                You inherit the parent's allowed tool and skill capability set, but you do not inherit the parent's message history.
                Use tools only when needed. Do not perform write operations. Report facts, uncertainty, and suggested next steps.

                Task-specific prompt:
                %s
                """.formatted(taskSpecificPrompt).trim();
    }
}
