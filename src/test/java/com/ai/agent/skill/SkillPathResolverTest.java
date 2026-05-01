package com.ai.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void blankPathReadsSkillMarkdown() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Path skillDir = Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Files.writeString(skillDir.resolve("SKILL.md"), "purchase guide content");

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThat(resolver.view("purchase-guide", " ")).isEqualTo("purchase guide content");
    }

    @Test
    void readsNormalRelativePathWithinSkillDirectory() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Path skillDir = Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Files.createDirectories(skillDir.resolve("references"));
        Files.writeString(skillDir.resolve("references/checklist.md"), "checklist content");

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThat(resolver.view("purchase-guide", "references/checklist.md")).isEqualTo("checklist content");
    }

    @Test
    void rejectsParentDirectoryTraversalWithStableCode() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Files.writeString(tempDir.resolve("secret.txt"), "secret");

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThatThrownBy(() -> resolver.view("purchase-guide", "../secret.txt"))
                .isInstanceOf(SkillPathException.class)
                .extracting("code")
                .isEqualTo(SkillPathErrorCode.PATH_ESCAPE);
    }

    @Test
    void rejectsAbsolutePathWithStableCode() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "secret");

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThatThrownBy(() -> resolver.view("purchase-guide", secret.toString()))
                .isInstanceOf(SkillPathException.class)
                .extracting("code")
                .isEqualTo(SkillPathErrorCode.PATH_ESCAPE);
    }

    @Test
    void rejectsSymlinkEscapeWithStableCode() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Path skillDir = Files.createDirectories(skillsRoot.resolve("purchase-guide"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(skillDir.resolve("outside-link"), outside);

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThatThrownBy(() -> resolver.view("purchase-guide", "outside-link/secret.txt"))
                .isInstanceOf(SkillPathException.class)
                .extracting("code")
                .isEqualTo(SkillPathErrorCode.PATH_ESCAPE);
    }

    @Test
    void rejectsNestedSkillNameWithStableCode() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Path nested = Files.createDirectories(skillsRoot.resolve("purchase-guide").resolve("references"));
        Files.writeString(nested.resolve("SKILL.md"), "nested content");

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThatThrownBy(() -> resolver.view("purchase-guide/references", "SKILL.md"))
                .isInstanceOf(SkillPathException.class)
                .extracting("code")
                .isEqualTo(SkillPathErrorCode.INVALID_SKILL_NAME);
    }

    @Test
    void rejectsInvalidSkillNameCharactersWithStableCode() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(skillsRoot.resolve("purchase-guide"));

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThatThrownBy(() -> resolver.view("Purchase Guide", "SKILL.md"))
                .isInstanceOf(SkillPathException.class)
                .extracting("code")
                .isEqualTo(SkillPathErrorCode.INVALID_SKILL_NAME);
    }

    @Test
    void rejectsMalformedSkillPathWithStableCode() throws IOException {
        Path skillsRoot = Files.createDirectories(tempDir.resolve("skills"));
        Files.createDirectories(skillsRoot.resolve("purchase-guide"));

        SkillPathResolver resolver = new SkillPathResolver(skillsRoot);

        assertThatThrownBy(() -> resolver.view("purchase-guide", "bad\u0000path"))
                .isInstanceOf(SkillPathException.class)
                .extracting("code")
                .isEqualTo(SkillPathErrorCode.PATH_ESCAPE);
    }
}
