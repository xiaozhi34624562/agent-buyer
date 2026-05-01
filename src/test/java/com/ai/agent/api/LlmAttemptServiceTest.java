package com.ai.agent.api;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmChatRequest;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.LlmProviderAdapter;
import com.ai.agent.llm.LlmProviderAdapterRegistry;
import com.ai.agent.llm.LlmStreamListener;
import com.ai.agent.llm.LlmStreamResult;
import com.ai.agent.llm.ProviderCallException;
import com.ai.agent.llm.ProviderErrorType;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.ContextCompactionDraft;
import com.ai.agent.trajectory.ContextCompactionRecord;
import com.ai.agent.trajectory.ContextCompactionStore;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmAttemptServiceTest {
    @Test
    void executeAttemptSelectsProviderFromRunContextAndWritesActualProvider() throws Exception {
        CapturingProvider qwen = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(new CapturingProvider("deepseek"), qwen)),
                trajectoryStore,
                new ObjectMapper(),
                record -> null
        );

        service.executeAttempt(
                "run-1",
                1,
                "att-1",
                "QWEN",
                "deepseek-reasoner",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink()
        );

        assertThat(qwen.requests).hasSize(1);
        assertThat(trajectoryStore.provider).isEqualTo("qwen");
        assertThat(trajectoryStore.status).isEqualTo("SUCCEEDED");
    }

    @Test
    void retryablePreStreamFailureFallsBackAndWritesBothAttemptsAndEvent() throws Exception {
        FailingProvider primary = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "DeepSeek retryable status 500", 500)
        );
        CapturingProvider fallback = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(primary, fallback)),
                trajectoryStore,
                new ObjectMapper(),
                record -> null
        );

        LlmStreamResult result = service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{}"),
                "deepseek-reasoner",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink()
        );

        assertThat(result.content()).isEqualTo("ok");
        assertThat(primary.requests).hasSize(1);
        assertThat(fallback.requests).hasSize(1);
        assertThat(fallback.requests.getFirst().model()).isEqualTo("qwen-default");
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::provider)
                .containsExactly("deepseek", "qwen");
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::model)
                .containsExactly("deepseek-reasoner", "qwen-default");
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::status)
                .containsExactly("FAILED", "SUCCEEDED");
        assertThat(trajectoryStore.events).extracting(EventRecord::eventType)
                .contains("llm_fallback");
        assertThat(trajectoryStore.attempts.getFirst().errorJson())
                .contains("\"providerErrorType\":\"RETRYABLE_PRE_STREAM\"")
                .contains("\"statusCode\":500");
        assertThat(trajectoryStore.events.getFirst().payloadJson())
                .contains("\"statusCode\":500")
                .doesNotContain("\"message\"");
    }

    @Test
    void fallbackEnabledFalseDoesNotFallback() {
        FailingProvider primary = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "429")
        );
        CapturingProvider fallback = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(primary, fallback)),
                trajectoryStore,
                new ObjectMapper(),
                record -> null
        );

        assertThatThrownBy(() -> service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{\"fallbackEnabled\":false}"),
                "deepseek-chat",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink()
        )).isInstanceOf(ProviderCallException.class);

        assertThat(primary.requests).hasSize(1);
        assertThat(fallback.requests).isEmpty();
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::provider)
                .containsExactly("deepseek");
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::status)
                .containsExactly("FAILED");
    }

    @Test
    void streamStartedFailureDoesNotFallback() {
        FailingProvider primary = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.STREAM_STARTED, "tool delta already emitted")
        );
        CapturingProvider fallback = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(primary, fallback)),
                trajectoryStore,
                new ObjectMapper(),
                record -> null
        );

        assertThatThrownBy(() -> service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{}"),
                "deepseek-chat",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink()
        )).isInstanceOf(ProviderCallException.class);

        assertThat(primary.requests).hasSize(1);
        assertThat(fallback.requests).isEmpty();
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::provider)
                .containsExactly("deepseek");
    }

    @Test
    void fallbackProviderSelectionComesFromRunContext() throws Exception {
        FailingProvider primary = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "5xx")
        );
        CapturingProvider qwen = new CapturingProvider("qwen");
        CapturingProvider configuredDefault = new CapturingProvider("configured-default");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(primary, qwen, configuredDefault)),
                trajectoryStore,
                new ObjectMapper(),
                record -> null
        );

        service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{}"),
                "qwen-max",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink()
        );

        assertThat(qwen.requests).hasSize(1);
        assertThat(configuredDefault.requests).isEmpty();
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::provider)
                .containsExactly("deepseek", "qwen");
    }

    @Test
    void fallbackAttemptReceivesOwnCompactionAttribution() throws Exception {
        FailingProvider primary = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "DeepSeek retryable status 500", 500)
        );
        CapturingProvider fallback = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        RecordingCompactionStore compactionStore = new RecordingCompactionStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(primary, fallback)),
                trajectoryStore,
                new ObjectMapper(),
                compactionStore
        );

        service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{}"),
                "deepseek-reasoner",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                List.of(new ContextCompactionDraft("SUMMARY_COMPACT", 100, 20, List.of("msg-old"))),
                new NoopAgentEventSink()
        );

        String fallbackAttemptId = trajectoryStore.attempts.get(1).attemptId();
        assertThat(compactionStore.records).extracting(ContextCompactionRecord::attemptId)
                .containsExactly("att-1", fallbackAttemptId);
        assertThat(compactionStore.records).extracting(ContextCompactionRecord::compactedMessageIds)
                .containsExactly(List.of("msg-old"), List.of("msg-old"));
    }

    @Test
    void fallbackBudgetRejectionDoesNotWriteFallbackEventOrAttempt() {
        FailingProvider primary = new FailingProvider(
                "deepseek",
                new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "DeepSeek retryable status 500", 500)
        );
        CapturingProvider fallback = new CapturingProvider("qwen");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(primary, fallback)),
                trajectoryStore,
                new ObjectMapper(),
                record -> null
        );

        assertThatThrownBy(() -> service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{}"),
                "deepseek-chat",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                new NoopAgentEventSink(),
                new FallbackRejectingObserver()
        )).isInstanceOf(LlmCallBudgetExceededException.class);

        assertThat(primary.requests).hasSize(1);
        assertThat(fallback.requests).isEmpty();
        assertThat(trajectoryStore.attempts).extracting(AttemptRecord::provider)
                .containsExactly("deepseek");
        assertThat(trajectoryStore.events).isEmpty();
    }

    @Test
    void retryBudgetRejectionAfterAcceptedProviderCallWritesFailedAttemptForCompactionAttribution() {
        BudgetRejectingRetryProvider provider = new BudgetRejectingRetryProvider("deepseek");
        RecordingTrajectoryStore trajectoryStore = new RecordingTrajectoryStore();
        RecordingCompactionStore compactionStore = new RecordingCompactionStore();
        LlmAttemptService service = new LlmAttemptService(
                new LlmProviderAdapterRegistry(List.of(provider)),
                trajectoryStore,
                new ObjectMapper(),
                compactionStore
        );

        assertThatThrownBy(() -> service.executeAttempt(
                "run-1",
                1,
                "att-1",
                runContext("deepseek", "qwen", "{}"),
                "deepseek-chat",
                null,
                List.of(LlmMessage.user("msg-1", "hello")),
                List.of(),
                List.of(new ContextCompactionDraft("LARGE_RESULT_SPILL", 120, 40, List.of("tool-1"))),
                new NoopAgentEventSink(),
                new FallbackRejectingObserver()
        )).isInstanceOf(LlmCallBudgetExceededException.class);

        assertThat(provider.requests).hasSize(1);
        assertThat(trajectoryStore.attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.attemptId()).isEqualTo("att-1");
            assertThat(attempt.status()).isEqualTo("FAILED");
            assertThat(attempt.errorJson()).contains("RUN_WIDE_BUDGET");
        });
        assertThat(compactionStore.records).singleElement().satisfies(record -> {
            assertThat(record.attemptId()).isEqualTo("att-1");
            assertThat(record.compactedMessageIds()).containsExactly("tool-1");
        });
    }

    private static RunContext runContext(String primaryProvider, String fallbackProvider, String providerOptions) {
        return new RunContext(
                "run-1",
                List.of(),
                "deepseek-chat",
                primaryProvider,
                fallbackProvider,
                providerOptions,
                4,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
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
        public String defaultModel() {
            return providerName + "-default";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            requests.add(request);
            return new LlmStreamResult("ok", List.of(), FinishReason.STOP, null, null);
        }
    }

    private static final class FailingProvider implements LlmProviderAdapter {
        private final String providerName;
        private final RuntimeException failure;
        private final List<LlmChatRequest> requests = new ArrayList<>();

        private FailingProvider(String providerName, RuntimeException failure) {
            this.providerName = providerName;
            this.failure = failure;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String defaultModel() {
            return providerName + "-default";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            requests.add(request);
            throw failure;
        }
    }

    private static final class BudgetRejectingRetryProvider implements LlmProviderAdapter {
        private final String providerName;
        private final List<LlmChatRequest> requests = new ArrayList<>();

        private BudgetRejectingRetryProvider(String providerName) {
            this.providerName = providerName;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String defaultModel() {
            return providerName + "-default";
        }

        @Override
        public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
            request.beforeProviderCall();
            requests.add(request);
            request.beforeProviderCall();
            throw new ProviderCallException(ProviderErrorType.RETRYABLE_PRE_STREAM, "unreachable");
        }
    }

    private static final class FallbackRejectingObserver implements com.ai.agent.llm.LlmCallObserver {
        private int calls;

        @Override
        public void beforeProviderCall() {
            calls++;
            if (calls > 1) {
                throw new LlmCallBudgetExceededException("RUN_WIDE_BUDGET", 1, 1);
            }
        }
    }

    private record AttemptRecord(String attemptId, String provider, String model, String status, String errorJson) {
    }

    private record EventRecord(String eventType, String payloadJson) {
    }

    private static final class RecordingCompactionStore implements ContextCompactionStore {
        private final List<ContextCompactionRecord> records = new ArrayList<>();

        @Override
        public String record(ContextCompactionRecord record) {
            records.add(record);
            return "cmp-" + records.size();
        }
    }

    private static final class RecordingTrajectoryStore implements TrajectoryStore, TrajectoryReader {
        private String provider;
        private String status;
        private final List<AttemptRecord> attempts = new ArrayList<>();
        private final List<EventRecord> events = new ArrayList<>();

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
            attempts.add(new AttemptRecord(attemptId, provider, model, status, errorJson));
        }

        @Override
        public void writeAgentEvent(String runId, String eventType, String payloadJson) {
            events.add(new EventRecord(eventType, payloadJson));
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
