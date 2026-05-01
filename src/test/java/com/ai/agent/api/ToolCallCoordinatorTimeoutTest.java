package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.Tool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolRegistry;
import com.ai.agent.tool.ToolSchema;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallCoordinatorTimeoutTest {
    @Test
    void effectiveToolTimeoutUsesSlowestToolSchemaTimeoutWhenItExceedsGlobalTimeout() {
        AgentProperties properties = new AgentProperties();
        properties.getAgentLoop().setToolResultTimeoutMs(90_000);
        ToolRegistry registry = new ToolRegistry(List.of(tool("agent_tool", Duration.ofMillis(185_000))));
        ToolCall call = new ToolCall(
                "run-1",
                "tc-1",
                1,
                "tu-1",
                "agent_tool",
                "agent_tool",
                "{}",
                false,
                false,
                false,
                null
        );

        Duration timeout = ToolCallCoordinator.effectiveToolResultTimeout(properties, registry, List.of(call));

        assertThat(timeout).isEqualTo(Duration.ofMillis(185_000));
    }

    private Tool tool(String name, Duration timeout) {
        return () -> new ToolSchema(
                name,
                "test tool",
                "{}",
                false,
                false,
                timeout,
                1024,
                List.of()
        );
    }
}
