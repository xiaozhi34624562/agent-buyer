package com.ai.agent.subagent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.subagent.model.SubAgentResult;
import com.ai.agent.subagent.model.SubAgentTask;
import com.ai.agent.subagent.runtime.SubAgentRunner;
import com.ai.agent.tool.core.CancellationToken;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolStatus;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.PiiMasker;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.FinalEvent;
import com.ai.agent.web.sse.TextDeltaEvent;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.ai.agent.web.sse.ToolResultEvent;
import com.ai.agent.web.sse.ToolUseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaDeclaresAgentToolAsHighCostNonConcurrentTool() {
        AgentTool tool = toolWithoutRunner();

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
        AgentTool tool = toolWithoutRunner();

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
        AgentTool tool = toolWithoutRunner();
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
                provider(new com.ai.agent.subagent.runtime.SubAgentRunner() {
                    @Override
                    public com.ai.agent.subagent.model.SubAgentResult run(
                            com.ai.agent.subagent.model.SubAgentTask task,
                            CancellationToken token
                    ) {
                        assertThat(task.parentRunId()).isEqualTo("run-1");
                        assertThat(task.parentToolCallId()).isEqualTo("tc-1");
                        assertThat(task.agentType()).isEqualTo("explore");
                        assertThat(task.task()).isEqualTo("find candidate orders");
                        return new com.ai.agent.subagent.model.SubAgentResult(
                                "child-run-1",
                                RunStatus.SUCCEEDED,
                                "found one candidate order",
                                false
                        );
                    }
                }),
                new AgentProperties()
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
                provider((task, token) -> new com.ai.agent.subagent.model.SubAgentResult(
                        "child-run-1",
                        RunStatus.TIMEOUT,
                        "SubAgent did not finish within the wait timeout.",
                        true
                )),
                new AgentProperties()
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

    private AgentTool toolWithoutRunner() {
        return new AgentTool(
                new PiiMasker(objectMapper),
                objectMapper,
                provider(null),
                new AgentProperties()
        );
    }

    private static final class NoopSink implements com.ai.agent.web.sse.AgentEventSink {
        @Override
        public void onTextDelta(com.ai.agent.web.sse.TextDeltaEvent event) {
        }

        @Override
        public void onToolUse(com.ai.agent.web.sse.ToolUseEvent event) {
        }

        @Override
        public void onToolProgress(com.ai.agent.web.sse.ToolProgressEvent event) {
        }

        @Override
        public void onToolResult(com.ai.agent.web.sse.ToolResultEvent event) {
        }

        @Override
        public void onFinal(com.ai.agent.web.sse.FinalEvent event) {
        }

        @Override
        public void onError(com.ai.agent.web.sse.ErrorEvent event) {
        }
    }
}
