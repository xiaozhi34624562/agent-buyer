package com.ai.agent.skill.tool;

import com.ai.agent.skill.core.SkillRegistry;
import com.ai.agent.skill.path.SkillPathException;
import com.ai.agent.skill.path.SkillPathResolver;
import com.ai.agent.tool.core.AbstractTool;
import com.ai.agent.tool.core.CancellationToken;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.PiiMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class SkillViewTool extends AbstractTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "skillName": {
                  "type": "string",
                  "description": "Skill name returned by skill_list, for example purchase-guide."
                },
                "skillPath": {
                  "type": "string",
                  "description": "Optional path inside the skill directory. Omit to read SKILL.md."
                }
              },
              "required": ["skillName"],
              "additionalProperties": false
            }
            """;

    private final SkillRegistry skillRegistry;
    private final SkillPathResolver skillPathResolver;

    public SkillViewTool(
            PiiMasker piiMasker,
            SkillRegistry skillRegistry,
            SkillPathResolver skillPathResolver,
            ObjectMapper objectMapper
    ) {
        super(piiMasker, objectMapper);
        this.skillRegistry = skillRegistry;
        this.skillPathResolver = skillPathResolver;
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "skill_view",
                "Read a skill's SKILL.md or a file inside that skill directory. Use skill_list first when unsure.",
                SCHEMA,
                true,
                true,
                Duration.ofSeconds(5),
                65_536,
                List.of()
        );
    }

    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            SkillViewArgs args = objectMapper.readValue(defaultJson(use.argsJson()), SkillViewArgs.class);
            if (args.skillName() == null || args.skillName().isBlank()) {
                return ToolValidation.rejected(errorJson("missing_skill_name", "skillName is required"));
            }
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return ToolValidation.rejected(errorJson("invalid_args", e.getMessage()));
        }
    }

    @Override
    protected ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws Exception {
        SkillViewArgs args = objectMapper.readValue(normalizedArgsJson, SkillViewArgs.class);
        if (!skillRegistry.contains(args.skillName())) {
            return ToolTerminal.failed(
                    running.call().toolCallId(),
                    errorJson("skill_not_found", "skill is not available to the current user")
            );
        }
        try {
            String content = skillPathResolver.view(args.skillName(), args.skillPath());
            String effectivePath = args.skillPath() == null || args.skillPath().isBlank()
                    ? "SKILL.md"
                    : args.skillPath();
            return ToolTerminal.succeeded(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                    "skillName", args.skillName(),
                    "skillPath", effectivePath,
                    "content", content
            )));
        } catch (SkillPathException e) {
            return ToolTerminal.failed(running.call().toolCallId(), errorJson(e.code().name(), e.getMessage()));
        }
    }

    private record SkillViewArgs(String skillName, String skillPath) {
    }
}
