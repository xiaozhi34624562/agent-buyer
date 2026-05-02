package com.ai.agent.loop;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.security.SensitivePayloadSanitizer;
import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.ToolResultCloser;
import com.ai.agent.tool.runtime.ToolResultWaiter;
import com.ai.agent.tool.runtime.ToolRuntime;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.FinalEvent;
import com.ai.agent.web.sse.TextDeltaEvent;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.ai.agent.web.sse.ToolResultEvent;
import com.ai.agent.web.sse.ToolUseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolCallCoordinatorMalformedArgsTest {
    @Test
    void malformedRejectedToolArgumentsAreStoredAsValidJsonPrecheckFailure() {
        TrajectoryStore trajectoryStore = mock(TrajectoryStore.class);
        TrajectoryReader trajectoryReader = mock(TrajectoryReader.class);
        ToolResultCloser closer = mock(ToolResultCloser.class);
        ObjectMapper objectMapper = new ObjectMapper();
        SensitivePayloadSanitizer sanitizer = new SensitivePayloadSanitizer(objectMapper);
        when(trajectoryReader.findToolCallsByRun("run-1")).thenReturn(List.of());
        ToolCallCoordinator coordinator = new ToolCallCoordinator(
                new AgentProperties(),
                new ToolRegistry(List.of(rejectingJsonTool())),
                mock(ToolRuntime.class),
                mock(RedisToolStore.class),
                mock(ToolResultWaiter.class),
                trajectoryStore,
                trajectoryReader,
                closer,
                mock(PendingConfirmToolStore.class),
                objectMapper,
                sanitizer
        );
        LlmStreamResult result = new LlmStreamResult(
                "更新第一步",
                List.of(new ToolCallMessage(
                        "call-1",
                        "todo_write",
                        "{\"stepId\":\"step_1\",\"status\":DONE}"
                )),
                FinishReason.TOOL_CALLS,
                null,
                null
        );
        ArgumentCaptor<LlmMessage> assistantCaptor = ArgumentCaptor.forClass(LlmMessage.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCall>> callsCaptor = ArgumentCaptor.forClass(List.class);

        coordinator.processToolCalls(
                "run-1",
                "demo-user",
                result,
                List.of(rejectingJsonTool()),
                new NoopSink()
        );

        verify(trajectoryStore).appendAssistantAndToolCalls(eq("run-1"), assistantCaptor.capture(), callsCaptor.capture());
        ToolCall storedCall = callsCaptor.getValue().getFirst();
        ToolCallMessage replayCall = assistantCaptor.getValue().toolCalls().getFirst();
        assertThat(storedCall.precheckFailed()).isTrue();
        assertThat(storedCall.argsJson()).isEqualTo("{}");
        assertThat(replayCall.argsJson()).isEqualTo("{}");
        verify(closer).closeTerminal(eq("run-1"), eq(storedCall), any(), any());
    }

    private Tool rejectingJsonTool() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                return new ToolSchema(
                        "todo_write",
                        "update todo",
                        "{}",
                        false,
                        true,
                        Duration.ofSeconds(5),
                        4096,
                        List.of()
                );
            }

            @Override
            public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
                return ToolValidation.rejected("{\"type\":\"invalid_args\"}");
            }
        };
    }

    private static final class NoopSink implements AgentEventSink {
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
