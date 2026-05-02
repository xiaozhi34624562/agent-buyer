package com.ai.agent.subagent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.subagent.model.SubAgentBudgetExceededException;
import com.ai.agent.subagent.model.SubAgentResult;
import com.ai.agent.subagent.model.SubAgentTask;
import com.ai.agent.subagent.runtime.SubAgentBudgetPolicy;
import com.ai.agent.subagent.runtime.SubAgentRunner;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class AgentTool extends AbstractTool {
    public static final String NAME = "agent_tool";
    public static final String ANTI_ABUSE_HINT = """
            AgentTool is a high-cost delegation tool. Use it only when a task truly needs an independent child context, such as long investigation or isolated sub-goal exploration. A single run can create at most 2 SubAgents; if the budget is exceeded, handle the task directly.
            """;
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "agentType": {
                  "type": "string",
                  "enum": ["explore"],
                  "description": "Type of SubAgent to create. Use explore for order/business investigation."
                },
                "task": {
                  "type": "string",
                  "description": "Self-contained task package for the SubAgent. Include the objective, constraints, and expected result summary."
                },
                "systemPrompt": {
                  "type": "string",
                  "description": "Optional task-specific system prompt. Keep it minimal and relevant."
                }
              },
              "required": ["agentType", "task"],
              "additionalProperties": false
            }
            """;

    private final ObjectProvider<SubAgentRunner> runnerProvider;
    private final AgentProperties properties;

    @Autowired
    public AgentTool(
            PiiMasker piiMasker,
            ObjectMapper objectMapper,
            ObjectProvider<SubAgentRunner> runnerProvider,
            AgentProperties properties
    ) {
        super(piiMasker, objectMapper);
        this.runnerProvider = runnerProvider;
        this.properties = properties == null ? new AgentProperties() : properties;
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                NAME,
                "High-cost tool that creates a synchronous SubAgent child run for independent context work. Use sparingly; single run limit is 2 child agents.",
                SCHEMA,
                false,
                false,
                Duration.ofMillis(properties.getSubAgent().getWaitTimeoutMs() + 5_000),
                65_536,
                List.of()
        );
    }

    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            AgentToolArgs args = objectMapper.readValue(defaultJson(use.argsJson()), AgentToolArgs.class);
            if (args.agentType() == null || args.agentType().isBlank()) {
                return ToolValidation.rejected(errorJson("missing_agent_type", "agentType is required"));
            }
            if (!"explore".equals(args.agentType())) {
                return ToolValidation.rejected(errorJson("unsupported_agent_type", "only explore agent is supported"));
            }
            if (args.task() == null || args.task().isBlank()) {
                return ToolValidation.rejected(errorJson("missing_task", "task is required"));
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
        SubAgentRunner runner = runnerProvider == null ? null : runnerProvider.getIfAvailable();
        if (runner == null) {
            return ToolTerminal.failed(
                    running.call().toolCallId(),
                    errorJson("subagent_not_ready", "SubAgent runner is not wired until V21-11")
            );
        }
        AgentToolArgs args = objectMapper.readValue(normalizedArgsJson, AgentToolArgs.class);
        try {
            SubAgentResult result = runner.run(new SubAgentTask(
                    ctx.runId(),
                    running.call().toolCallId(),
                    ctx.userId(),
                    args.agentType(),
                    args.task(),
                    args.systemPrompt(),
                    Collections.emptyList()
            ), token);
            if (result.status() == RunStatus.SUCCEEDED) {
                return ToolTerminal.succeeded(running.call().toolCallId(), objectMapper.writeValueAsString(resultPayload(result)));
            }
            return ToolTerminal.failed(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                    "type", subAgentErrorType(result.status()),
                    "childRunId", result.childRunId() == null ? "" : result.childRunId(),
                    "status", result.status().name(),
                    "summary", result.summary() == null ? "" : result.summary(),
                    "partial", result.partial()
            )));
        } catch (SubAgentBudgetExceededException e) {
            return ToolTerminal.failed(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                    "type", SubAgentBudgetPolicy.SUBAGENT_BUDGET_EXCEEDED,
                    "reason", e.reserveResult().reason().name()
            )));
        }
    }

    private Map<String, Object> resultPayload(SubAgentResult result) {
        return Map.of(
                "childRunId", result.childRunId() == null ? "" : result.childRunId(),
                "status", result.status().name(),
                "summary", result.summary() == null ? "" : result.summary(),
                "partial", result.partial()
        );
    }

    private String subAgentErrorType(RunStatus status) {
        if (status == RunStatus.TIMEOUT) {
            return "SUBAGENT_WAIT_TIMEOUT";
        }
        if (status == RunStatus.PAUSED) {
            return "INTERRUPTED_PARTIAL";
        }
        if (status == RunStatus.CANCELLED) {
            return "SUBAGENT_CANCELLED";
        }
        return "SUBAGENT_FAILED";
    }

    private record AgentToolArgs(String agentType, String task, String systemPrompt) {
    }
}
