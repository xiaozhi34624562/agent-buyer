package com.ai.agent.skill.tool;

import com.ai.agent.skill.core.SkillRegistry;
import com.ai.agent.tool.core.AbstractTool;
import com.ai.agent.tool.core.CancellationToken;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.security.PiiMasker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class SkillListTool extends AbstractTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """;

    private final SkillRegistry skillRegistry;

    public SkillListTool(PiiMasker piiMasker, SkillRegistry skillRegistry, ObjectMapper objectMapper) {
        super(piiMasker, objectMapper);
        this.skillRegistry = skillRegistry;
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "skill_list",
                "List skills available to the current user. The userId is injected by runtime and must not be provided as an argument.",
                SCHEMA,
                true,
                true,
                Duration.ofSeconds(5),
                16_384,
                List.of()
        );
    }

    @Override
    protected ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws Exception {
        String resultJson = objectMapper.writeValueAsString(Map.of(
                "userId", ctx.userId(),
                "skills", skillRegistry.previews()
        ));
        return ToolTerminal.succeeded(running.call().toolCallId(), resultJson);
    }
}
