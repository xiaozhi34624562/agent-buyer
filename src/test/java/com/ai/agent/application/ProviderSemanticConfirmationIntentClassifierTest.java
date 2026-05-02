package com.ai.agent.application;

import com.ai.agent.application.HumanIntentResolver.ConfirmationDecision;
import com.ai.agent.application.HumanIntentResolver.ConfirmationIntent;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.model.LlmChatRequest;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.LlmStreamResult;
import com.ai.agent.llm.provider.LlmProviderAdapter;
import com.ai.agent.llm.provider.LlmProviderAdapterRegistry;
import com.ai.agent.llm.provider.LlmStreamListener;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.security.PendingConfirmToolStore;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderSemanticConfirmationIntentClassifierTest {
    @Test
    void parsesJsonDecisionFromProviderText() {
        FakeProvider provider = new FakeProvider("deepseek", """
                {"intent":"CONFIRM","confidence":0.88,"question":""}
                """);
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        ProviderSemanticConfirmationIntentClassifier classifier = new ProviderSemanticConfirmationIntentClassifier(
                new LlmProviderAdapterRegistry(List.of(provider)),
                trajectoryStore,
                mock(PendingConfirmToolStore.class),
                new ObjectMapper()
        );

        ConfirmationDecision decision = classifier.classify(
                "run-1",
                "user-1",
                runContext("run-1"),
                "没问题，就处理吧"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.CONFIRM);
        assertThat(decision.confidence()).isEqualTo(0.88);
        assertThat(provider.requests).singleElement().satisfies(request -> {
            assertThat(request.temperature()).isZero();
            assertThat(request.maxTokens()).isEqualTo(256);
            assertThat(request.tools()).isEmpty();
            assertThat(request.messages()).extracting(LlmMessage::role)
                    .extracting(Enum::name)
                    .containsExactly("SYSTEM", "USER");
        });
        assertThat(trajectoryStore.events).singleElement().satisfies(event ->
                assertThat(event).contains("CONFIRM")
        );
        assertThat(trajectoryStore.attempts).singleElement().satisfies(attempt ->
                assertThat(attempt).contains("deepseek", "SUCCEEDED")
        );
    }

    @Test
    void malformedProviderTextReturnsClarification() {
        FakeProvider provider = new FakeProvider("deepseek", "我觉得可以");
        ProviderSemanticConfirmationIntentClassifier classifier = new ProviderSemanticConfirmationIntentClassifier(
                new LlmProviderAdapterRegistry(List.of(provider)),
                mock(TrajectoryStore.class),
                mock(PendingConfirmToolStore.class),
                new ObjectMapper()
        );

        ConfirmationDecision decision = classifier.classify(
                "run-1",
                "user-1",
                runContext("run-1"),
                "行吧"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.CLARIFY);
        assertThat(decision.question()).contains("请明确");
    }

    @Test
    void fallsBackToConfiguredProviderWhenPrimaryFailsBeforeDecision() {
        FakeProvider primary = new FakeProvider("deepseek", null);
        primary.fail = true;
        FakeProvider fallback = new FakeProvider("qwen", """
                {"intent":"REJECT","confidence":0.93,"question":""}
                """);
        ProviderSemanticConfirmationIntentClassifier classifier = new ProviderSemanticConfirmationIntentClassifier(
                new LlmProviderAdapterRegistry(List.of(primary, fallback)),
                mock(TrajectoryStore.class),
                mock(PendingConfirmToolStore.class),
                new ObjectMapper()
        );

        ConfirmationDecision decision = classifier.classify(
                "run-1",
                "user-1",
                runContext("run-1"),
                "算了吧"
        );

        assertThat(decision.intent()).isEqualTo(ConfirmationIntent.REJECT);
        assertThat(primary.requests).hasSize(1);
        assertThat(fallback.requests).hasSize(1);
    }

    @Test
    void providerPromptIncludesPendingActionContext() {
        FakeProvider provider = new FakeProvider("deepseek", """
                {"intent":"CONFIRM","confidence":0.91,"question":""}
                """);
        PendingConfirmToolStore pendingStore = mock(PendingConfirmToolStore.class);
        when(pendingStore.load("run-1")).thenReturn(new PendingConfirmToolStore.PendingConfirmTool(
                "tc-1",
                "cancel_order",
                "{\"orderId\":\"O-1001\",\"confirmToken\":\"secret-token\"}",
                "secret-token",
                "将取消订单 O-1001（Noise cancelling earbuds，金额 129.90）。",
                System.currentTimeMillis() + 60_000
        ));
        ProviderSemanticConfirmationIntentClassifier classifier = new ProviderSemanticConfirmationIntentClassifier(
                new LlmProviderAdapterRegistry(List.of(provider)),
                mock(TrajectoryStore.class),
                pendingStore,
                new ObjectMapper()
        );

        classifier.classify(
                "run-1",
                "user-1",
                runContext("run-1"),
                "没问题，按刚才的取消方案继续处理"
        );

        assertThat(provider.requests).singleElement().satisfies(request -> {
            String prompt = request.messages().get(1).content();
            assertThat(prompt)
                    .contains("pendingToolName: cancel_order")
                    .contains("pendingSummary: 将取消订单 O-1001")
                    .contains("pendingArgsJson");
            assertThat(prompt).doesNotContain("secret-token");
        });
    }

    private static RunContext runContext(String runId) {
        return new RunContext(
                runId,
                List.of("query_order", "cancel_order"),
                "deepseek-reasoner",
                "deepseek",
                "qwen",
                "{}",
                10,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private static final class FakeProvider implements LlmProviderAdapter {
        private final String providerName;
        private final String content;
        private final List<LlmChatRequest> requests = new ArrayList<>();
        private boolean fail;

        private FakeProvider(String providerName, String content) {
            this.providerName = providerName;
            this.content = content;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String defaultModel() {
            return providerName + "-model";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            requests.add(request);
            if (fail) {
                throw new IllegalStateException("provider failed");
            }
            return new LlmStreamResult(content, List.of(), FinishReason.STOP, null, "{}");
        }
    }

    private static final class RecordingTrajectoryStore implements TrajectoryStore {
        private final List<String> events = new ArrayList<>();
        private final List<String> attempts = new ArrayList<>();

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
        public String appendMessage(String runId, LlmMessage message) {
            return message.messageId();
        }

        @Override
        public void writeLlmAttempt(String attemptId, String runId, int turnNo, String provider, String model, String status, FinishReason finishReason, Integer promptTokens, Integer completionTokens, Integer totalTokens, String errorJson, String rawDiagnosticJson) {
            attempts.add(provider + ":" + status + ":" + finishReason);
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
        public void writeAgentEvent(String runId, String eventType, String payloadJson) {
            events.add(eventType + ":" + payloadJson);
        }

        @Override
        public int currentTurn(String runId) {
            return 0;
        }

        @Override
        public String findRunUserId(String runId) {
            return "user-1";
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            return RunStatus.RUNNING;
        }
    }
}
