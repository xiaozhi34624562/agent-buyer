package com.ai.agent.skill;

import com.ai.agent.api.AgentEventSink;
import com.ai.agent.api.ErrorEvent;
import com.ai.agent.api.FinalEvent;
import com.ai.agent.api.TextDeltaEvent;
import com.ai.agent.api.ToolProgressEvent;
import com.ai.agent.api.ToolResultEvent;
import com.ai.agent.api.ToolUseEvent;
import com.ai.agent.tool.CancellationToken;
import com.ai.agent.tool.PiiMasker;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolExecutionContext;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolUse;
import com.ai.agent.tool.ToolUseContext;
import com.ai.agent.tool.ToolValidation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillToolsTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path skillsRoot;

    @Test
    void skillListHasNoUserIdParameterAndReturnsVisiblePreviews() throws Exception {
        writeSkill("purchase-guide", "买货指南", "full purchase content");
        writeSkill("return-exchange-guide", "退换货指南", "full return content");
        SkillListTool tool = new SkillListTool(
                new PiiMasker(objectMapper),
                new SkillRegistry(skillsRoot),
                objectMapper
        );

        assertThat(tool.schema().parametersJsonSchema()).doesNotContain("userId");
        ToolValidation validation = tool.validate(new ToolUseContext("run-1", "user-1"), new ToolUse("tu-1", "skill_list", "{}"));

        assertThat(validation.accepted()).isTrue();
        String resultJson = tool.run(
                new ToolExecutionContext("run-1", "user-1", NoopSink.INSTANCE),
                started("call-1", "skill_list", validation.normalizedArgsJson()),
                () -> false
        ).resultJson();

        JsonNode result = objectMapper.readTree(resultJson);
        assertThat(result.get("userId").asText()).isEqualTo("user-1");
        assertThat(result.get("skills")).hasSize(2);
        assertThat(resultJson).contains("purchase-guide", "return-exchange-guide");
        assertThat(resultJson).doesNotContain("full purchase content", "full return content");
    }

    @Test
    void skillViewReadsSkillMarkdownAndNestedFile() throws Exception {
        writeSkill("purchase-guide", "买货指南", "full purchase content");
        Files.createDirectories(skillsRoot.resolve("purchase-guide").resolve("references"));
        Files.writeString(skillsRoot.resolve("purchase-guide").resolve("references/checklist.md"), "checklist content");
        SkillViewTool tool = new SkillViewTool(
                new PiiMasker(objectMapper),
                new SkillRegistry(skillsRoot),
                new SkillPathResolver(skillsRoot),
                objectMapper
        );

        String rootResult = runView(tool, "{\"skillName\":\"purchase-guide\"}");
        assertThat(rootResult).contains("\"skillName\":\"purchase-guide\"");
        assertThat(rootResult).contains("full purchase content");

        String nestedResult = runView(tool, "{\"skillName\":\"purchase-guide\",\"skillPath\":\"references/checklist.md\"}");
        assertThat(nestedResult).contains("\"skillPath\":\"references/checklist.md\"");
        assertThat(nestedResult).contains("checklist content");
    }

    @Test
    void skillViewValidationRejectsMissingSkillName() {
        SkillViewTool tool = new SkillViewTool(
                new PiiMasker(objectMapper),
                new SkillRegistry(skillsRoot),
                new SkillPathResolver(skillsRoot),
                objectMapper
        );

        ToolValidation validation = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "skill_view", "{}")
        );

        assertThat(validation.accepted()).isFalse();
        assertThat(validation.errorJson()).contains("missing_skill_name");
    }

    @Test
    void skillViewMapsPathErrorsToToolFailure() throws Exception {
        writeSkill("purchase-guide", "买货指南", "full purchase content");
        SkillViewTool tool = new SkillViewTool(
                new PiiMasker(objectMapper),
                new SkillRegistry(skillsRoot),
                new SkillPathResolver(skillsRoot),
                objectMapper
        );
        ToolValidation validation = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "skill_view", "{\"skillName\":\"purchase-guide\",\"skillPath\":\"../secret.txt\"}")
        );

        assertThat(validation.accepted()).isTrue();
        var terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", NoopSink.INSTANCE),
                started("call-1", "skill_view", validation.normalizedArgsJson()),
                () -> false
        );

        assertThat(terminal.status()).isEqualTo(ToolStatus.FAILED);
        assertThat(terminal.errorJson()).contains("PATH_ESCAPE");
    }

    @Test
    void skillViewCannotReadSkillOutsideEnabledRegistry() throws Exception {
        writeSkill("purchase-guide", "买货指南", "full purchase content");
        writeSkill("java-alibaba-review", "代码审查", "review content");
        SkillViewTool tool = new SkillViewTool(
                new PiiMasker(objectMapper),
                new SkillRegistry(skillsRoot, List.of("purchase-guide")),
                new SkillPathResolver(skillsRoot),
                objectMapper
        );
        ToolValidation validation = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "skill_view", "{\"skillName\":\"java-alibaba-review\"}")
        );

        var terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", NoopSink.INSTANCE),
                started("call-1", "skill_view", validation.normalizedArgsJson()),
                () -> false
        );

        assertThat(terminal.status()).isEqualTo(ToolStatus.FAILED);
        assertThat(terminal.errorJson()).contains("skill_not_found");
        assertThat(terminal.errorJson()).doesNotContain("review content");
    }

    private String runView(SkillViewTool tool, String argsJson) throws Exception {
        ToolValidation validation = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "skill_view", argsJson)
        );
        assertThat(validation.accepted()).isTrue();
        return tool.run(
                new ToolExecutionContext("run-1", "user-1", NoopSink.INSTANCE),
                started("call-1", "skill_view", validation.normalizedArgsJson()),
                () -> false
        ).resultJson();
    }

    private StartedTool started(String toolCallId, String toolName, String argsJson) {
        return new StartedTool(
                new ToolCall("run-1", toolCallId, 1L, "tu-1", toolName, toolName, argsJson, true, true, false, null),
                1,
                "lease",
                System.currentTimeMillis() + 60_000L,
                "test-worker"
        );
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

    private enum NoopSink implements AgentEventSink {
        INSTANCE;

        @Override
        public void onTextDelta(TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
        }

        @Override
        public void onFinal(FinalEvent event) {
        }

        @Override
        public void onError(ErrorEvent event) {
        }
    }
}
