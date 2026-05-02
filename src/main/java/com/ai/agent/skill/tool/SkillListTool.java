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

/**
 * 技能列表工具。
 * 列出当前用户可用的所有技能。
 */
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

    /**
     * 获取工具Schema定义。
     *
     * @return 工具Schema
     */
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

    /**
     * 执行技能列表查询。
     *
     * @param ctx              工具执行上下文
     * @param running          已启动的工具实例
     * @param normalizedArgsJson 标准化后的参数JSON
     * @param token            取消令牌
     * @return 工具执行结果
     */
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
