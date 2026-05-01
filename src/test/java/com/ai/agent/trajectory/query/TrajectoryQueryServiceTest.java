package com.ai.agent.trajectory.query;

import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentLlmAttemptEntity;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.trajectory.dto.AgentRunTrajectoryDto;
import com.ai.agent.trajectory.dto.MessageToolCallDto;
import com.ai.agent.trajectory.dto.ToolCallDto;
import com.ai.agent.trajectory.model.TrajectorySnapshot;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrajectoryQueryServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void returnsSanitizedDtoWithoutRawEntitiesOrSensitivePayloads() throws Exception {
        TrajectoryQueryService service = new TrajectoryQueryService(new FakeTrajectoryReader(), objectMapper);

        AgentRunTrajectoryDto dto = service.getTrajectory("run-1");
        String json = objectMapper.writeValueAsString(dto);

        assertThat(dto.run()).isNotInstanceOf(AgentRunEntity.class);
        assertThat(dto.run().agentType()).isEqualTo("SUBAGENT");
        assertThat(dto.run().parentRunId()).isEqualTo("parent-run-1");
        assertThat(dto.run().parentToolCallId()).isEqualTo("parent-tool-call-1");
        assertThat(dto.run().parentLinkStatus()).isEqualTo("LIVE");
        assertThat(dto.messages()).allSatisfy(message -> assertThat(message).isNotInstanceOf(AgentMessageEntity.class));
        assertThat(dto.llmAttempts()).allSatisfy(attempt -> assertThat(attempt).isNotInstanceOf(AgentLlmAttemptEntity.class));
        assertThat(dto.toolCalls()).allSatisfy(toolCall -> assertThat(toolCall).isNotInstanceOf(AgentToolCallTraceEntity.class));
        assertThat(dto.toolResults()).allSatisfy(toolResult -> assertThat(toolResult).isNotInstanceOf(AgentToolResultTraceEntity.class));
        assertThat(dto.events()).allSatisfy(event -> assertThat(event).isNotInstanceOf(AgentEventEntity.class));
        assertThat(dto.toolProgress()).allSatisfy(progress -> assertThat(progress).isNotInstanceOf(AgentToolProgressEntity.class));
        assertThat(dto.compactions()).allSatisfy(compaction -> assertThat(compaction).isNotInstanceOf(AgentContextCompactionEntity.class));

        assertThat(recordComponents(MessageToolCallDto.class)).containsExactly("toolUseId", "toolName");
        assertThat(recordComponents(ToolCallDto.class)).doesNotContain("argsJson");
        assertThat(dto.messages().getFirst().toolCalls())
                .containsExactly(new MessageToolCallDto("tool-use-1", "cancel_order"));
        assertThat(dto.compactions()).hasSize(1)
                .first()
                .satisfies(compaction -> {
                    assertThat(compaction.turnNo()).isEqualTo(2);
                    assertThat(compaction.attemptId()).isEqualTo("att-compact-1");
                    assertThat(compaction.strategy()).isEqualTo("summary");
                    assertThat(compaction.beforeTokens()).isEqualTo(1200);
                    assertThat(compaction.afterTokens()).isEqualTo(420);
                    assertThat(compaction.compactedMessageIds()).containsExactly("msg-1", "msg-2");
                });

        assertThat(json)
                .doesNotContain("confirmToken")
                .doesNotContain("ct_secret")
                .doesNotContain("rawDiagnosticJson")
                .doesNotContain("argsJson")
                .doesNotContain("resultJson")
                .doesNotContain("errorJson")
                .doesNotContain("sk-test-secret")
                .doesNotContain("13800138000")
                .doesNotContain("demo@example.com");
        assertThat(json).contains("contentPreview", "preview", "precheckErrorPreview");
    }

    private static List<String> recordComponents(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .toList();
    }

    private static final class FakeTrajectoryReader implements TrajectoryReader {
        @Override
        public List<LlmMessage> loadMessages(String runId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public List<ToolCall> findToolCallsByRun(String runId) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public TrajectorySnapshot loadTrajectorySnapshot(String runId) {
            LocalDateTime now = LocalDateTime.of(2026, 5, 1, 12, 0);
            AgentRunEntity run = new AgentRunEntity();
            run.setRunId(runId);
            run.setUserId("demo-user");
            run.setStatus(RunStatus.WAITING_USER_CONFIRMATION.name());
            run.setTurnNo(2);
            run.setParentRunId("parent-run-1");
            run.setParentToolCallId("parent-tool-call-1");
            run.setAgentType("SUBAGENT");
            run.setParentLinkStatus("LIVE");
            run.setStartedAt(now.minusMinutes(2));
            run.setUpdatedAt(now.minusMinutes(1));
            run.setLastError("{\"confirmToken\":\"ct_secret_run\",\"message\":\"sk-test-secret\"}");

            AgentMessageEntity message = new AgentMessageEntity();
            message.setMessageId("msg-1");
            message.setRunId(runId);
            message.setSeq(1L);
            message.setRole("ASSISTANT");
            message.setContent("Use confirmToken ct_secret_message to cancel.");
            message.setToolCalls("""
                    [{"id":"ignored","name":"cancel_order","argsJson":{"orderId":"O-1001","confirmToken":"ct_secret_message_tool"},"toolUseId":"tool-use-1"}]
                    """);
            message.setCreatedAt(now);

            AgentLlmAttemptEntity attempt = new AgentLlmAttemptEntity();
            attempt.setAttemptId("att-1");
            attempt.setRunId(runId);
            attempt.setTurnNo(1);
            attempt.setProvider("deepseek");
            attempt.setModel("deepseek-reasoner");
            attempt.setStatus("FAILED");
            attempt.setErrorJson("{\"message\":\"sk-test-secret\",\"confirmToken\":\"ct_secret_attempt\"}");
            attempt.setRawDiagnosticJson("{\"request\":\"full provider payload\"}");
            attempt.setStartedAt(now.minusSeconds(20));
            attempt.setCompletedAt(now.minusSeconds(10));

            AgentToolCallTraceEntity call = new AgentToolCallTraceEntity();
            call.setToolCallId("tool-call-1");
            call.setRunId(runId);
            call.setMessageId("msg-1");
            call.setSeq(1L);
            call.setToolUseId("tool-use-1");
            call.setRawToolName("cancel_order");
            call.setToolName("cancel_order");
            call.setArgsJson("{\"orderId\":\"O-1001\",\"confirmToken\":\"ct_secret_args\",\"phone\":\"13800138000\"}");
            call.setConcurrent(false);
            call.setIdempotent(false);
            call.setPrecheckFailed(true);
            call.setPrecheckErrorJson("{\"confirmToken\":\"ct_secret_precheck\",\"email\":\"demo@example.com\",\"message\":\"bad args\"}");
            call.setCreatedAt(now);

            AgentToolResultTraceEntity result = new AgentToolResultTraceEntity();
            result.setResultId("res-1");
            result.setToolCallId("tool-call-1");
            result.setRunId(runId);
            result.setToolUseId("tool-use-1");
            result.setStatus("SUCCEEDED");
            result.setResultJson("{\"confirmToken\":\"ct_secret_result\",\"phone\":\"13800138000\",\"summary\":\"dry run ok\"}");
            result.setErrorJson("{\"confirmToken\":\"ct_secret_error\",\"message\":\"hidden error\"}");
            result.setSynthetic(false);
            result.setCreatedAt(now);

            AgentEventEntity event = new AgentEventEntity();
            event.setEventId("evt-1");
            event.setRunId(runId);
            event.setEventType("tool_result");
            event.setPayloadJson("""
                    {"argsJson":{"confirmToken":"ct_secret_event_args"},"resultJson":{"confirmToken":"ct_secret_event_result"},"errorJson":{"message":"hidden"},"rawDiagnosticJson":{"request":"full"},"email":"demo@example.com","summary":"recorded"}
                    """);
            event.setCreatedAt(now);

            AgentToolProgressEntity progress = new AgentToolProgressEntity();
            progress.setProgressId("prog-1");
            progress.setRunId(runId);
            progress.setToolCallId("tool-call-1");
            progress.setStage("precheck");
            progress.setMessage("checking confirmToken ct_secret_progress for 13800138000");
            progress.setPercent(50);
            progress.setCreatedAt(now);

            AgentContextCompactionEntity compaction = new AgentContextCompactionEntity();
            compaction.setCompactionId("cmp-1");
            compaction.setRunId(runId);
            compaction.setTurnNo(2);
            compaction.setAttemptId("att-compact-1");
            compaction.setStrategy("summary");
            compaction.setBeforeTokens(1200);
            compaction.setAfterTokens(420);
            compaction.setCompactedMessageIds("[\"msg-1\",\"msg-2\"]");
            compaction.setCreatedAt(now.plusSeconds(1));

            return new TrajectorySnapshot(
                    run,
                    List.of(message),
                    List.of(attempt),
                    List.of(call),
                    List.of(result),
                    List.of(event),
                    List.of(progress),
                    List.of(compaction)
            );
        }
    }
}
