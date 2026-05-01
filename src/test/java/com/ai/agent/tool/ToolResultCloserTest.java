package com.ai.agent.tool;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectorySnapshot;
import com.ai.agent.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultCloserTest {
    @Test
    void syntheticCloseWritesToolResultAndToolMessageOnlyOnce() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        ToolResultCloser closer = new ToolResultCloser(store, store);
        String runId = "run-close";
        ToolCall call = new ToolCall(
                runId,
                "tc-1",
                1,
                "tool-use-1",
                "cancel_order",
                "cancel_order",
                "{}",
                false,
                false,
                false,
                null
        );
        store.appendMessage(runId, LlmMessage.assistant(
                "msg-assistant",
                "",
                List.of(new ToolCallMessage(call.toolUseId(), call.toolName(), call.argsJson()))
        ));

        closer.closeSynthetic(
                runId,
                List.of(call),
                CancelReason.RUN_ABORTED,
                "{\"type\":\"run_aborted\"}",
                null
        );
        closer.closeSynthetic(
                runId,
                List.of(call),
                CancelReason.RUN_ABORTED,
                "{\"type\":\"run_aborted\"}",
                null
        );

        assertThat(store.toolResults).hasSize(1);
        assertThat(store.messages.stream().filter(message -> message.role() == MessageRole.TOOL)).hasSize(1);
        new com.ai.agent.llm.TranscriptPairValidator().validate(store.messages);
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private final List<LlmMessage> messages = new ArrayList<>();
        private final List<AgentToolResultTraceEntity> toolResults = new ArrayList<>();

        @Override
        public List<LlmMessage> loadMessages(String runId) {
            return List.copyOf(messages);
        }

        @Override
        public List<ToolCall> findToolCallsByRun(String runId) {
            return List.of();
        }

        @Override
        public TrajectorySnapshot loadTrajectorySnapshot(String runId) {
            AgentRunEntity run = new AgentRunEntity();
            run.setRunId(runId);
            run.setStatus(RunStatus.RUNNING.name());
            List<AgentMessageEntity> messageEntities = messages.stream()
                    .map(message -> {
                        AgentMessageEntity entity = new AgentMessageEntity();
                        entity.setMessageId(message.messageId());
                        entity.setRunId(runId);
                        entity.setRole(message.role().name());
                        entity.setContent(message.content());
                        entity.setToolUseId(message.toolUseId());
                        return entity;
                    })
                    .toList();
            return new TrajectorySnapshot(run, messageEntities, List.of(), List.of(), toolResults, List.of(), List.of());
        }

        @Override
        public void createRun(String runId, String userId) {
        }

        @Override
        public void updateRunStatus(String runId, RunStatus status, String error) {
        }

        @Override
        public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
            return false;
        }

        @Override
        public int nextTurn(String runId) {
            return 0;
        }

        @Override
        public int currentTurn(String runId) {
            return 0;
        }

        @Override
        public String findRunUserId(String runId) {
            return "demo-user";
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            return RunStatus.RUNNING;
        }

        @Override
        public String appendMessage(String runId, LlmMessage message) {
            messages.add(message);
            return message.messageId();
        }

        @Override
        public void writeLlmAttempt(
                String attemptId,
                String runId,
                int turnNo,
                String provider,
                String model,
                String status,
                FinishReason finishReason,
                Integer promptTokens,
                Integer completionTokens,
                Integer totalTokens,
                String errorJson,
                String rawDiagnosticJson
        ) {
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
            appendMessage(runId, assistant);
            return assistant.messageId();
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
            AgentToolResultTraceEntity entity = new AgentToolResultTraceEntity();
            entity.setToolCallId(terminal.toolCallId());
            entity.setRunId(runId);
            entity.setToolUseId(toolUseId);
            entity.setStatus(terminal.status().name());
            entity.setErrorJson(terminal.errorJson());
            entity.setSynthetic(terminal.synthetic());
            toolResults.removeIf(existing -> existing.getToolCallId().equals(terminal.toolCallId()));
            toolResults.add(entity);
        }
    }
}
