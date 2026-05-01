package com.ai.agent;

import com.ai.agent.api.RunRepairService;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ConfirmTokenStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RunRepairServiceIntegrationTest {
    @Autowired
    TrajectoryStore trajectoryStore;

    @Autowired
    TrajectoryReader trajectoryReader;

    @Autowired
    AgentRunMapper runMapper;

    @Autowired
    RunRepairService repairService;

    @Test
    void startupRepairClosesMissingToolResultsWithSyntheticResult() {
        String runId = Ids.newId("test_run");
        String toolUseId = Ids.newId("call");
        String toolCallId = Ids.newId("tc");

        trajectoryStore.createRun(runId, "demo-user");
        trajectoryStore.updateRunStatus(runId, RunStatus.RUNNING, null);
        trajectoryStore.appendAssistantAndToolCalls(
                runId,
                LlmMessage.assistant(Ids.newId("msg"), "", List.of(new ToolCallMessage(toolUseId, "query_order", "{}"))),
                List.of(new ToolCall(
                        runId,
                        toolCallId,
                        1,
                        toolUseId,
                        "query_order",
                        "query_order",
                        "{}",
                        true,
                        true,
                        false,
                        null
                ))
        );
        runMapper.update(null, new LambdaUpdateWrapper<AgentRunEntity>()
                .set(AgentRunEntity::getUpdatedAt, LocalDateTime.now().minusMinutes(5))
                .eq(AgentRunEntity::getRunId, runId));

        repairService.repairNowForTests();

        assertThat(trajectoryStore.findRunStatus(runId)).isEqualTo(RunStatus.FAILED_RECOVERED);
        assertThat(trajectoryReader.loadTrajectorySnapshot(runId).toolResults()).hasSize(1);
    }

    @Test
    void confirmationExpirySkipsSideEffectsWhenStatusCasFails() {
        String runId = "run-confirm-race";
        AgentRunEntity candidate = new AgentRunEntity();
        candidate.setRunId(runId);
        FakeTrajectoryStore fakeStore = new FakeTrajectoryStore();
        FakeStringRedisTemplate redisTemplate = new FakeStringRedisTemplate();
        RunRepairService service = new RunRepairService(
                new com.ai.agent.config.AgentProperties(),
                mapperProxy(AgentRunMapper.class, "findExpiredConfirmationRuns", List.of(candidate)),
                mapperProxy(com.ai.agent.persistence.mapper.AgentToolCallTraceMapper.class, null, null),
                fakeStore,
                new ConfirmTokenStore(new com.ai.agent.config.AgentProperties(), redisTemplate, new ObjectMapper())
        );

        service.expireWaitingConfirmations();

        assertThat(fakeStore.transitions).containsExactly(
                "transition:" + runId + ":WAITING_USER_CONFIRMATION->TIMEOUT"
        );
        assertThat(fakeStore.appendedMessages).isEmpty();
        assertThat(redisTemplate.deleteCount).isZero();
    }

    @SuppressWarnings("unchecked")
    private static <T> T mapperProxy(Class<T> type, String handledMethod, Object handledReturn) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals(handledMethod)) {
                        return handledReturn;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        return null;
    }

    private static final class FakeStringRedisTemplate extends StringRedisTemplate {
        private int deleteCount;

        @Override
        public Boolean delete(String key) {
            deleteCount++;
            return true;
        }
    }

    private static final class FakeTrajectoryStore implements TrajectoryStore {
        private final List<String> transitions = new ArrayList<>();
        private final List<LlmMessage> appendedMessages = new ArrayList<>();

        @Override
        public boolean transitionRunStatus(String runId, RunStatus expected, RunStatus next, String error) {
            transitions.add("transition:" + runId + ":" + expected + "->" + next);
            return false;
        }

        @Override
        public String appendMessage(String runId, LlmMessage message) {
            appendedMessages.add(message);
            return message.messageId();
        }

        @Override
        public void createRun(String runId, String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateRunStatus(String runId, RunStatus status, String error) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int nextTurn(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int currentTurn(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String findRunUserId(String runId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunStatus findRunStatus(String runId) {
            return RunStatus.CANCELLED;
        }

        @Override
        public void writeLlmAttempt(String attemptId, String runId, int turnNo, String provider, String model, String status, com.ai.agent.domain.FinishReason finishReason, Integer promptTokens, Integer completionTokens, Integer totalTokens, String errorJson, String rawDiagnosticJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeToolCall(String messageId, ToolCall call) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String appendAssistantAndToolCalls(String runId, LlmMessage assistant, List<ToolCall> toolCalls) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeToolResult(String runId, String toolUseId, com.ai.agent.tool.ToolTerminal terminal) {
            throw new UnsupportedOperationException();
        }

    }
}
