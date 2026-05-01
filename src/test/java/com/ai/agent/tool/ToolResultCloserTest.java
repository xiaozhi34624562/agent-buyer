package com.ai.agent.tool;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.support.TestObjectProvider;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectorySnapshot;
import com.ai.agent.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultCloserTest {
    @Test
    void syntheticCloseWritesToolResultAndToolMessageOnlyOnce() {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        ToolResultCloser closer = new ToolResultCloser(store, store, TestObjectProvider.empty());
        String runId = "run-close";
        ToolCall call = toolCall(runId);
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

    @Test
    void concurrentTerminalCloseWritesToolMessageOnlyOnce() throws Exception {
        FakeTrajectoryStore store = new FakeTrajectoryStore();
        store.snapshotDelayMillis = 25L;
        ToolResultCloser closer = new ToolResultCloser(store, store, TestObjectProvider.empty());
        String runId = "run-concurrent-close";
        ToolCall call = toolCall(runId);
        store.appendMessage(runId, LlmMessage.assistant(
                "msg-assistant",
                "",
                List.of(new ToolCallMessage(call.toolUseId(), call.toolName(), call.argsJson()))
        ));

        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                closer.closeTerminal(runId, call, ToolTerminal.succeeded(call.toolCallId(), "{\"ok\":true}"), null);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(3, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertThat(store.toolResults).hasSize(1);
        assertThat(store.messages.stream().filter(message -> message.role() == MessageRole.TOOL)).hasSize(1);
        new com.ai.agent.llm.TranscriptPairValidator().validate(store.messages);
    }

    private static ToolCall toolCall(String runId) {
        return new ToolCall(
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
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private final List<LlmMessage> messages = new ArrayList<>();
        private final List<AgentToolResultTraceEntity> toolResults = new ArrayList<>();
        private volatile long snapshotDelayMillis;

        @Override
        public synchronized List<LlmMessage> loadMessages(String runId) {
            return List.copyOf(messages);
        }

        @Override
        public List<ToolCall> findToolCallsByRun(String runId) {
            return List.of();
        }

        @Override
        public TrajectorySnapshot loadTrajectorySnapshot(String runId) {
            if (snapshotDelayMillis > 0) {
                try {
                    Thread.sleep(snapshotDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            AgentRunEntity run = new AgentRunEntity();
            run.setRunId(runId);
            run.setStatus(RunStatus.RUNNING.name());
            List<AgentMessageEntity> messageEntities;
            List<AgentToolResultTraceEntity> toolResultEntities;
            synchronized (this) {
                messageEntities = messages.stream()
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
                toolResultEntities = List.copyOf(toolResults);
            }
            return new TrajectorySnapshot(run, messageEntities, List.of(), List.of(), toolResultEntities, List.of(), List.of(), List.of());
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
        public synchronized String appendMessage(String runId, LlmMessage message) {
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
        public synchronized void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
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
