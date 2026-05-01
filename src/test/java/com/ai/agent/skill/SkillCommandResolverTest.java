package com.ai.agent.skill;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.LlmMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCommandResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path skillsRoot;

    @Test
    void slashSkillInjectsFullSkillMarkdownForLatestUserMessageOnly() throws IOException {
        writeSkill("purchase-guide", "买货指南", "full purchase content");
        SkillCommandResolver resolver = resolver(defaultProperties());
        List<LlmMessage> messages = List.of(
                LlmMessage.user("u-old", "/purchase-guide old"),
                LlmMessage.assistant("a1", "ok", List.of()),
                LlmMessage.user("u-new", "please use /purchase-guide")
        );

        SkillCommandResolution resolution = resolver.resolve(messages);

        assertThat(resolution.skillNames()).containsExactly("purchase-guide");
        assertThat(resolution.messages()).hasSize(1);
        assertThat(resolution.messages().getFirst().content())
                .contains("<skill name=\"purchase-guide\">")
                .contains("full purchase content")
                .contains("</skill>");
        assertThat(resolution.messages().getFirst().extras()).containsEntry("transientSkill", true);
    }

    @Test
    void tooManySlashSkillsFailsClosedWithStructuredBudgetError() throws IOException {
        writeSkill("purchase-guide", "买货指南", "purchase");
        writeSkill("return-exchange-guide", "退换货指南", "return");
        writeSkill("order-issue-support", "订单问题指南", "order");
        AgentProperties properties = defaultProperties();
        properties.getSkills().setMaxPerMessage(2);
        SkillCommandResolver resolver = resolver(properties);

        assertThatThrownBy(() -> resolver.resolve(List.of(LlmMessage.user(
                "u1",
                "/purchase-guide /return-exchange-guide /order-issue-support"
        ))))
                .isInstanceOf(SkillCommandException.class)
                .hasMessageContaining("SKILL_BUDGET_EXCEEDED")
                .hasMessageContaining("\"budget\":2")
                .hasMessageContaining("\"actual\":3")
                .hasMessageContaining("\"exceeded\":1");
    }

    @Test
    void tooManySkillTokensFailsClosedWithStructuredBudgetError() throws IOException {
        writeSkill("purchase-guide", "买货指南", "word ".repeat(200));
        AgentProperties properties = defaultProperties();
        properties.getSkills().setMaxTokenPerMessage(20);
        SkillCommandResolver resolver = resolver(properties);

        assertThatThrownBy(() -> resolver.resolve(List.of(LlmMessage.user("u1", "/purchase-guide"))))
                .isInstanceOf(SkillCommandException.class)
                .hasMessageContaining("SKILL_BUDGET_EXCEEDED")
                .hasMessageContaining("\"budget\":20")
                .hasMessageContaining("\"matchedSkills\"");
    }

    @Test
    void disabledSlashSkillFailsClosedWithoutLoadingContent() throws IOException {
        writeSkill("purchase-guide", "买货指南", "purchase");
        writeSkill("java-alibaba-review", "代码审查", "review content");
        AgentProperties properties = defaultProperties();
        SkillCommandResolver resolver = new SkillCommandResolver(
                properties,
                new SkillRegistry(skillsRoot, List.of("purchase-guide")),
                new SkillPathResolver(skillsRoot),
                objectMapper
        );

        assertThatThrownBy(() -> resolver.resolve(List.of(LlmMessage.user("u1", "/java-alibaba-review"))))
                .isInstanceOf(SkillCommandException.class)
                .hasMessageContaining("slash skill is not available")
                .hasMessageNotContaining("review content");
    }

    private SkillCommandResolver resolver(AgentProperties properties) {
        return new SkillCommandResolver(
                properties,
                new SkillRegistry(skillsRoot),
                new SkillPathResolver(skillsRoot),
                objectMapper
        );
    }

    private AgentProperties defaultProperties() {
        AgentProperties properties = new AgentProperties();
        properties.getSkills().setEnabledSkillNames(List.of(
                "purchase-guide",
                "return-exchange-guide",
                "order-issue-support"
        ));
        return properties;
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
}
