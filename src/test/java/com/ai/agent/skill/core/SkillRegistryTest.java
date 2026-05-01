package com.ai.agent.skill.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillRegistryTest {
    @TempDir
    Path skillsRoot;

    @Test
    void scansAnthropicStyleSkillsAndReturnsPreviewsOnly() throws IOException {
        writeSkill("purchase-guide", "purchase-guide", "买货前需求澄清", "full purchase instructions");
        writeSkill("return-exchange-guide", "return-exchange-guide", "退换货判断", "full return instructions");
        writeSkill("order-issue-support", "order-issue-support", "订单问题处理", "full order instructions");

        SkillRegistry registry = new SkillRegistry(skillsRoot);

        assertThat(registry.previews())
                .extracting(SkillPreview::name)
                .containsExactly("order-issue-support", "purchase-guide", "return-exchange-guide");
        assertThat(registry.previews())
                .extracting(SkillPreview::description)
                .containsExactly("订单问题处理", "买货前需求澄清", "退换货判断");
        assertThat(registry.previews())
                .extracting(SkillPreview::toString)
                .noneMatch(text -> text.contains("full purchase instructions"))
                .noneMatch(text -> text.contains("full return instructions"))
                .noneMatch(text -> text.contains("full order instructions"));
    }

    @Test
    void failsWithStableCodeWhenFrontmatterIsMissing() throws IOException {
        Path skillDir = Files.createDirectories(skillsRoot.resolve("broken-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"), "# Broken\n\nmissing frontmatter");

        assertThatThrownBy(() -> new SkillRegistry(skillsRoot))
                .isInstanceOf(SkillRegistryException.class)
                .extracting("code")
                .isEqualTo(SkillRegistryErrorCode.SKILL_FRONTMATTER_MISSING);
    }

    @Test
    void failsWithStableCodeWhenRequiredFrontmatterFieldsAreMissing() throws IOException {
        Path skillDir = Files.createDirectories(skillsRoot.resolve("broken-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: broken-skill
                ---

                # Broken
                """);

        assertThatThrownBy(() -> new SkillRegistry(skillsRoot))
                .isInstanceOf(SkillRegistryException.class)
                .extracting("code")
                .isEqualTo(SkillRegistryErrorCode.SKILL_FRONTMATTER_INVALID);
    }

    @Test
    void rejectsSymlinkSkillDirectoryWithStableCode() throws IOException {
        Path outside = Files.createDirectories(skillsRoot.resolveSibling("outside-skill"));
        writeSkillFile(outside.resolve("SKILL.md"), "outside-skill", "outside metadata", "outside body");
        Files.createSymbolicLink(skillsRoot.resolve("outside-skill"), outside);

        assertThatThrownBy(() -> new SkillRegistry(skillsRoot))
                .isInstanceOf(SkillRegistryException.class)
                .extracting("code")
                .isEqualTo(SkillRegistryErrorCode.SKILL_PATH_ESCAPE);
    }

    @Test
    void rejectsSymlinkSkillMarkdownWithStableCode() throws IOException {
        Path outside = Files.createDirectories(skillsRoot.resolveSibling("outside"));
        writeSkillFile(outside.resolve("SKILL.md"), "purchase-guide", "outside metadata", "outside body");
        Path skillDir = Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Files.createSymbolicLink(skillDir.resolve("SKILL.md"), outside.resolve("SKILL.md"));

        assertThatThrownBy(() -> new SkillRegistry(skillsRoot))
                .isInstanceOf(SkillRegistryException.class)
                .extracting("code")
                .isEqualTo(SkillRegistryErrorCode.SKILL_PATH_ESCAPE);
    }

    private void writeSkill(String directory, String name, String description, String body) throws IOException {
        Path skillDir = Files.createDirectories(skillsRoot.resolve(directory));
        writeSkillFile(skillDir.resolve("SKILL.md"), name, description, body);
    }

    private void writeSkillFile(Path skillFile, String name, String description, String body) throws IOException {
        Files.writeString(skillFile, """
                ---
                name: %s
                description: %s
                ---

                # %s

                %s
                """.formatted(name, description, name, body));
    }
}
