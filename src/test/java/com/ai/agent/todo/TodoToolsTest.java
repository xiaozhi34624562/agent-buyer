package com.ai.agent.todo;

import com.ai.agent.api.AgentEventSink;
import com.ai.agent.api.ErrorEvent;
import com.ai.agent.api.FinalEvent;
import com.ai.agent.api.TextDeltaEvent;
import com.ai.agent.api.ToolProgressEvent;
import com.ai.agent.api.ToolResultEvent;
import com.ai.agent.api.ToolUseEvent;
import com.ai.agent.tool.PiiMasker;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolExecutionContext;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.ToolUse;
import com.ai.agent.tool.ToolUseContext;
import com.ai.agent.tool.ToolValidation;
import com.ai.agent.trajectory.TrajectoryWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TodoToolsTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final TodoStore store = mock(TodoStore.class);
    private final RecordingWriter writer = new RecordingWriter();

    @Test
    void createToolValidatesItemsAndCreatesPlanFromCurrentRun() throws Exception {
        ToDoCreateTool tool = new ToDoCreateTool(new PiiMasker(objectMapper), store, objectMapper, writer);
        when(store.replacePlan("run-1", List.of(new TodoDraft("查询昨天订单", null))))
                .thenReturn(List.of(new TodoStep("step_1", "查询昨天订单", TodoStatus.PENDING, null, null)));

        ToolValidation missing = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "todo_create", "{}")
        );
        ToolTerminal terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", NoopSink.INSTANCE),
                started("todo_create", "{\"items\":[{\"title\":\"查询昨天订单\"}]}"),
                () -> false
        );

        assertThat(missing.accepted()).isFalse();
        assertThat(missing.errorJson()).contains("missing_items");
        assertThat(terminal.status()).isEqualTo(ToolStatus.SUCCEEDED);
        assertThat(terminal.resultJson()).contains("step_1", "查询昨天订单");
        assertThat(writer.events).containsExactly("run-1:todo_created");
    }

    @Test
    void writeToolUpdatesStepStatusAndRejectsUnknownStatus() throws Exception {
        ToDoWriteTool tool = new ToDoWriteTool(new PiiMasker(objectMapper), store, objectMapper, writer);
        when(store.updateStep("run-1", "step_1", TodoStatus.DONE, "已完成"))
                .thenReturn(new TodoStep("step_1", "查询昨天订单", TodoStatus.DONE, "已完成", null));

        ToolValidation invalid = tool.validate(
                new ToolUseContext("run-1", "user-1"),
                new ToolUse("tu-1", "todo_write", "{\"stepId\":\"step_1\",\"status\":\"UNKNOWN\"}")
        );
        ToolTerminal terminal = tool.run(
                new ToolExecutionContext("run-1", "user-1", NoopSink.INSTANCE),
                started("todo_write", "{\"stepId\":\"step_1\",\"status\":\"DONE\",\"notes\":\"已完成\"}"),
                () -> false
        );

        assertThat(invalid.accepted()).isFalse();
        assertThat(invalid.errorJson()).contains("invalid_status");
        assertThat(terminal.status()).isEqualTo(ToolStatus.SUCCEEDED);
        assertThat(terminal.resultJson()).contains("DONE", "step_1");
        verify(store).updateStep("run-1", "step_1", TodoStatus.DONE, "已完成");
        assertThat(writer.events).containsExactly("run-1:todo_updated");
    }

    private StartedTool started(String toolName, String argsJson) {
        ToolCall call = new ToolCall(
                "run-1",
                "tc-1",
                1,
                "tu-1",
                toolName,
                toolName,
                argsJson,
                false,
                false,
                false,
                null
        );
        return new StartedTool(call, 1, "lease-1", 1000L, "worker-1");
    }

    private static final class RecordingWriter implements TrajectoryWriter {
        private final List<String> events = new ArrayList<>();

        @Override
        public void createRun(String runId, String userId) {
        }

        @Override
        public void updateRunStatus(String runId, com.ai.agent.domain.RunStatus status, String error) {
        }

        @Override
        public boolean transitionRunStatus(String runId, com.ai.agent.domain.RunStatus expected, com.ai.agent.domain.RunStatus next, String error) {
            return false;
        }

        @Override
        public int nextTurn(String runId) {
            return 0;
        }

        @Override
        public String appendMessage(String runId, com.ai.agent.llm.LlmMessage message) {
            return null;
        }

        @Override
        public void writeLlmAttempt(String attemptId, String runId, int turnNo, String provider, String model, String status, com.ai.agent.domain.FinishReason finishReason, Integer promptTokens, Integer completionTokens, Integer totalTokens, String errorJson, String rawDiagnosticJson) {
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, com.ai.agent.llm.LlmMessage assistant, List<ToolCall> toolCalls) {
            return null;
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
        }

        @Override
        public void writeAgentEvent(String runId, String eventType, String payloadJson) {
            events.add(runId + ":" + eventType);
        }
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
