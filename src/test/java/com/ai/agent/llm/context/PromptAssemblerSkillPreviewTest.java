package com.ai.agent.llm.context;

import com.ai.agent.business.user.UserProfile;
import com.ai.agent.skill.core.SkillRegistry;
import com.ai.agent.subagent.tool.AgentTool;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerSkillPreviewTest {
    @TempDir
    Path skillsRoot;

    @Test
    void injectsSkillPreviewBetweenUserProfileAndToolSchemaWithoutFullSkillContent() throws IOException {
        writeSkill("purchase-guide", "买货指南", "full purchase content");
        writeSkill("return-exchange-guide", "退换货指南", "full return content");
        PromptAssembler assembler = new PromptAssembler(
                userId -> new UserProfile(userId, "Owner", null, null, null, "buyer"),
                new SkillRegistry(skillsRoot, List.of("purchase-guide", "return-exchange-guide"))
        );

        String prompt = assembler.materializeSystemPrompt("user-1", List.of(new StubTool("query_order")));

        assertThat(prompt).contains("Available skill preview");
        assertThat(prompt).contains("- purchase-guide: 买货指南");
        assertThat(prompt).contains("- return-exchange-guide: 退换货指南");
        assertThat(prompt).doesNotContain("full purchase content", "full return content");
        assertThat(prompt.indexOf("Current user profile:"))
                .isLessThan(prompt.indexOf("Available skill preview"));
        assertThat(prompt.indexOf("Available skill preview"))
                .isLessThan(prompt.indexOf("Available tool schema snapshot:"));
    }

    @Test
    void oneArgumentConstructorKeepsPromptAssemblyWorkingForTestsWithoutSkillRegistry() {
        PromptAssembler assembler = new PromptAssembler(
                userId -> new UserProfile(userId, "Owner", null, null, null, "buyer")
        );

        String prompt = assembler.materializeSystemPrompt("user-1", List.of(new StubTool("query_order")));

        assertThat(prompt).contains("Current user profile:");
        assertThat(prompt).contains("AgentTool is high-cost");
        assertThat(prompt).contains("single run can create at most 2 SubAgents");
        assertThat(prompt).doesNotContain("Available skill preview");
    }

    private void writeSkill(String name, String description, String body) throws IOException {
        Path skillDir = Files.createDirectories(skillsRoot.resolve(name));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---

                # %s

                %s
                """.formatted(name, description, name, body));
    }

    private record StubTool(String name) implements Tool {
        @Override
        public ToolSchema schema() {
            return new ToolSchema(
                    name,
                    "stub tool",
                    "{}",
                    true,
                    true,
                    Duration.ofSeconds(5),
                    4096,
                    List.of()
            );
        }
    }
}
