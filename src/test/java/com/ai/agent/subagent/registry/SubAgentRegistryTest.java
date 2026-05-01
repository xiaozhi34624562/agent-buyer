package com.ai.agent.subagent.registry;

import com.ai.agent.subagent.model.SubAgentTask;
import com.ai.agent.subagent.profile.ExploreAgentProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubAgentRegistryTest {
    @Test
    void exploreAgentInheritsReadAndSkillToolsButNotWriteTools() {
        ExploreAgentProfile profile = new ExploreAgentProfile();
        SubAgentTask task = new SubAgentTask(
                "parent-run",
                "parent-tool-call",
                "user-1",
                "explore",
                "find candidate orders",
                "focus on yesterday",
                List.of("query_order", "cancel_order", "skill_list", "skill_view", "agent_tool")
        );

        assertThat(profile.allowedToolNames(task.effectiveAllowedTools()))
                .containsExactly("query_order", "skill_list", "skill_view");
        assertThat(profile.renderSystemPrompt(task))
                .contains("isolated context")
                .contains("do not inherit the parent's message history")
                .contains("focus on yesterday");
    }

    @Test
    void registryResolvesExploreProfileAndRejectsUnknownTypes() {
        SubAgentRegistry registry = new SubAgentRegistry(List.of(new ExploreAgentProfile()));

        assertThat(registry.resolve("explore")).isInstanceOf(ExploreAgentProfile.class);
        assertThatThrownBy(() -> registry.resolve("writer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown subagent profile");
    }
}
