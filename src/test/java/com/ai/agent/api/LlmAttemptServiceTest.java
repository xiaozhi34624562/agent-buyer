package com.ai.agent.api;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.LlmStreamListener;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAttemptServiceTest {
    @Test
    void executeAttemptSelectsProviderFromRunContextAndWritesActualProvider() throws Exception {
        CapturingProvider qwen = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(new CapturingProvider("deepseek"), qwen)),
                trajectoryStore,
                new ObjectMapper()
        );

        service.executeAttempt(
                "run-1",
                1,
                "att-1",
                "QWEN",
                "qwen-max",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink()
        );

        assertThat(qwen.requests).hasSize(1);
        assertThat(trajectoryStore.provider).isEqualTo("qwen");
        assertThat(trajectoryStore.status).isEqualTo("SUCCEEDED");
    }

    private static final class CapturingProvider implements LlmProviderAdapter {
        private final String providerName;
        private final List<LlmChatRequest> requests = new ArrayList<>();

        private CapturingProvider(String providerName) {
            this.providerName = providerName;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            requests.add(request);
            return new LlmStreamResult("ok", List.of(), FinishReason.STOP, null, null);
        }
    }

    private static final class RecordingTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private String provider;
        private String status;

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
        public String appendMessage(String runId, LlmMessage message) {
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
            this.provider = provider;
            this.status = status;
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
            return assistant.messageId();
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, ToolTerminal terminal) {
        }

        @Override
        public List<LlmMessage> loadMessages(String runId) {
            return List.of();
        }

        @Override
        public List<ToolCall> findToolCallsByRun(String runId) {
            return List.of();
        }

        @Override
        public String findRunUserId(String runId) {
            return "user";
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            return RunStatus.RUNNING;
        }
    }

    private static final class NoopAgentEventSink implements AgentEventSink {
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
