package com.ai.agent.skill;

import com.ai.agent.tool.AbstractTool;
import com.ai.agent.tool.CancellationToken;
import com.ai.agent.tool.PiiMasker;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolExecutionContext;
import com.ai.agent.tool.ToolSchema;
import com.ai.agent.tool.ToolTerminal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

    public SkillListTool(PiiMasker piiMasker, SkillRegistry skillRegistry, ObjectMapper objectMapper) {
        super(piiMasker);
        this.skillRegistry = skillRegistry;
        this.objectMapper = objectMapper;
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
