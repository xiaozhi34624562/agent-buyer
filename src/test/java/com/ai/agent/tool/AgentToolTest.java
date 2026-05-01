package com.ai.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaDeclaresAgentToolAsHighCostNonConcurrentTool() {
        AgentTool tool = new AgentTool(new PiiMasker(objectMapper), objectMapper);

        ToolSchema schema = tool.schema();
        AgentProperties properties = new AgentProperties();

        assertThat(schema.name()).isEqualTo("agent_tool");
        assertThat(schema.description())
                .containsIgnoringCase("high-cost")
                .contains("single run limit");
        assertThat(schema.isConcurrent()).isFalse();
        assertThat(schema.timeout().toMillis()).isGreaterThan(properties.getSubAgent().getWaitTimeoutMs());
        assertThat(schema.parametersJsonSchema()).contains("agentType", "task", "explore");
    }

    @Test
    void validatesRequiredTaskPackage() {
        AgentTool tool = new AgentTool(new PiiMasker(objectMapper), objectMapper);

        ToolValidation missingTask = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "agent_tool", "{\"agentType\":\"explore\"}")
        );
        ToolValidation accepted = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "agent_tool", "{\"agentType\":\"explore\",\"task\":\"find candidate orders\"}")
        );

        assertThat(missingTask.accepted()).isFalse();
        assertThat(missingTask.errorJson()).contains("missing_task");
        assertThat(accepted.accepted()).isTrue();
    }

    @Test
    void runFailsClosedBeforeSubAgentRunnerIsWired() throws Exception {
        AgentTool tool = new AgentTool(new PiiMasker(objectMapper), objectMapper);
        ToolCall call = new ToolCall(
                "run-1",
                "tc-1",
                1,
                "tu-1",
                "agent_tool",
                "agent_tool",
                "{\"agentType\":\"explore\",\"task\":\"find candidate orders\"}",
                false,
                false,
                false,
                null
        );

        ToolTerminal terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", new NoopSink()),
                new StartedTool(call, 1, "lease-1", 1000L, "worker-1"),
                () -> false
        );

        assertThat(terminal.status()).isEqualTo(ToolStatus.FAILED);
        assertThat(terminal.errorJson()).contains("subagent_not_ready");
    }

    @Test
    void runDelegatesToSubAgentRunnerWhenAvailable() throws Exception {
        AgentTool tool = new AgentTool(
                new PiiMasker(objectMapper),
                objectMapper,
                provider(new com.ai.agent.subagent.SubAgentRunner() {
                    @Override
                    public com.ai.agent.subagent.SubAgentResult run(
                            com.ai.agent.subagent.SubAgentTask task,
                            CancellationToken token
                    ) {
                        assertThat(task.parentRunId()).isEqualTo("run-1");
                        assertThat(task.parentToolCallId()).isEqualTo("tc-1");
                        assertThat(task.agentType()).isEqualTo("explore");
                        assertThat(task.task()).isEqualTo("find candidate orders");
                        return new com.ai.agent.subagent.SubAgentResult(
                                "child-run-1",
                                RunStatus.SUCCEEDED,
                                "found one candidate order",
                                false
                        );
                    }
                })
        );
        ToolCall call = new ToolCall(
                "run-1",
                "tc-1",
                1,
                "tu-1",
                "agent_tool",
                "agent_tool",
                "{\"agentType\":\"explore\",\"task\":\"find candidate orders\"}",
                false,
                false,
                false,
                null
        );

        ToolTerminal terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", new NoopSink()),
                new StartedTool(call, 1, "lease-1", 1000L, "worker-1"),
                () -> false
        );

        assertThat(terminal.status()).isEqualTo(ToolStatus.SUCCEEDED);
        assertThat(terminal.resultJson())
                .contains("child-run-1")
                .contains("found one candidate order");
    }

    @Test
    void runMapsTimedOutChildRunToFailedToolResult() throws Exception {
        AgentTool tool = new AgentTool(
                new PiiMasker(objectMapper),
                objectMapper,
                provider((task, token) -> new com.ai.agent.subagent.SubAgentResult(
                        "child-run-1",
                        RunStatus.TIMEOUT,
                        "SubAgent did not finish within the wait timeout.",
                        true
                ))
        );
        ToolCall call = new ToolCall(
                "run-1",
                "tc-1",
                1,
                "tu-1",
                "agent_tool",
                "agent_tool",
                "{\"agentType\":\"explore\",\"task\":\"find candidate orders\"}",
                false,
                false,
                false,
                null
        );

        ToolTerminal terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", new NoopSink()),
                new StartedTool(call, 1, "lease-1", 1000L, "worker-1"),
                () -> false
        );

        assertThat(terminal.status()).isEqualTo(ToolStatus.FAILED);
        assertThat(terminal.errorJson())
                .contains("SUBAGENT_WAIT_TIMEOUT")
                .contains("child-run-1")
                .contains("SubAgent did not finish within the wait timeout.");
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static final class NoopSink implements com.ai.agent.api.AgentEventSink {
        @Override
        public void onTextDelta(com.ai.agent.api.TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(com.ai.agent.api.ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(com.ai.agent.api.ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(com.ai.agent.api.ToolResultEvent event) {
        }

        @Override
        public void onFinal(com.ai.agent.api.FinalEvent event) {
        }

        @Override
        public void onError(com.ai.agent.api.ErrorEvent event) {
        }
    }
}
