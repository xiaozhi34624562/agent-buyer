package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSummaryGeneratorTest {

    @Test
    void generatesStrictJsonSummaryThroughConfiguredProvider() {
        AgentProperties properties = new AgentProperties();
        properties.getLlm().setProvider("qwen");
        FakeProviderAdapter qwen = new FakeProviderAdapter("qwen", """
                {"summaryText":"用户要取消订单","businessFacts":["用户查询了订单"],"toolFacts":["query_order returned order O1"],"openQuestions":[],"compactedMessageIds":["u1","t1"]}
                """.trim());
        ProviderSummaryGenerator generator = new ProviderSummaryGenerator(
                properties,
                new LlmProviderAdapterRegistry(List.of(new FakeProviderAdapter("deepseek", "{}"), qwen)),
                new RecordingTrajectoryStore(),
                new ObjectMapper()
        );

        String summary = generator.generate("run-1", List.of(
                LlmMessage.user("u1", "取消昨天那个订单"),
                LlmMessage.tool("t1", "call-1", "{\"orderId\":\"O1\",\"status\":\"PAID\"}")
        ));

        assertThat(summary).contains("\"businessFacts\"");
        LlmChatRequest request = qwen.lastRequest();
        assertThat(request.runId()).isEqualTo("run-1");
        assertThat(request.model()).isEqualTo("qwen-model");
        assertThat(request.tools()).isEmpty();
        assertThat(request.temperature()).isEqualTo(0.0);
        assertThat(request.maxTokens()).isEqualTo(properties.getContext().getSummaryMaxTokens());
        assertThat(request.messages()).hasSize(2);
        assertThat(request.messages().get(0).role()).isEqualTo(MessageRole.SYSTEM);
        assertThat(request.messages().get(0).content()).contains("summaryText", "businessFacts", "toolFacts", "openQuestions");
        assertThat(request.messages().get(1).content()).contains("\"messageId\":\"u1\"", "\"messageId\":\"t1\"");
    }

    @Test
    void writesSummaryLlmAttemptWithBudgetObserver() {
        AgentProperties properties = new AgentProperties();
        FakeProviderAdapter provider = new FakeProviderAdapter("deepseek", """
                {"summaryText":"ok","businessFacts":[],"toolFacts":[],"openQuestions":[],"compactedMessageIds":["u1"]}
                """.trim());
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        CountingObserver observer = new CountingObserver();
        ProviderSummaryGenerator generator = new ProviderSummaryGenerator(
                properties,
                new LlmProviderAdapterRegistry(List.of(provider)),
                trajectoryStore,
                new ObjectMapper()
        );

        generator.generate(
                new SummaryGenerationContext("run-1", 3, observer),
                List.of(LlmMessage.user("u1", "hello"))
        );

        assertThat(observer.calls).isEqualTo(1);
        assertThat(trajectoryStore.attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.runId()).isEqualTo("run-1");
            assertThat(attempt.turnNo()).isEqualTo(3);
            assertThat(attempt.provider()).isEqualTo("deepseek");
            assertThat(attempt.status()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void rejectsProviderSummaryThatReturnsToolCalls() {
        FakeProviderAdapter provider = new FakeProviderAdapter(
                "deepseek",
                "",
                List.of(new ToolCallMessage("call-1", "query_order", "{}"))
        );
        ProviderSummaryGenerator generator = new ProviderSummaryGenerator(
                new AgentProperties(),
                new LlmProviderAdapterRegistry(List.of(provider)),
                new RecordingTrajectoryStore(),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> generator.generate("run-1", List.of(LlmMessage.user("u1", "hello"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("summary provider returned tool calls");
    }

    private static final class FakeProviderAdapter implements LlmProviderAdapter {
        private final String providerName;
        private final String content;
        private final List<ToolCallMessage> toolCalls;
        private final AtomicReference<LlmChatRequest> lastRequest = new AtomicReference<>();

        private FakeProviderAdapter(String providerName, String content) {
            this(providerName, content, List.of());
        }

        private FakeProviderAdapter(String providerName, String content, List<ToolCallMessage> toolCalls) {
            this.providerName = providerName;
            this.content = content;
            this.toolCalls = toolCalls;
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
            request.beforeProviderCall();
            lastRequest.set(request);
            listener.onTextDelta(content);
            return new LlmStreamResult(content, toolCalls, FinishReason.STOP, null, "{}");
        }

        private LlmChatRequest lastRequest() {
            return lastRequest.get();
        }
    }

    private static final class CountingObserver implements LlmCallObserver {
        private int calls;

        @Override
        public void beforeProviderCall() {
            calls++;
        }
    }

    private record AttemptRecord(String runId, int turnNo, String provider, String status) {
    }

    private static final class RecordingTrajectoryStore implements TrajectoryStore {
        private final List<AttemptRecord> attempts = new ArrayList<>();

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
            attempts.add(new AttemptRecord(runId, turnNo, provider, status));
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
        public int currentTurn(String runId) {
            return 0;
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
}
