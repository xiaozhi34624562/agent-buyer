package com.ai.agent;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrajectoryStoreIntegrationTest {
    @Autowired
    TrajectoryStore trajectoryStore;

    @Test
    void loadTrajectoryReturnsRunMessagesAttemptsToolCallsAndResults() {
        String runId = Ids.newId("test_run");
        String toolUseId = Ids.newId("call");
        String toolCallId = Ids.newId("tc");

        trajectoryStore.createRun(runId, "demo-user");
        trajectoryStore.appendMessage(runId, LlmMessage.system(Ids.newId("msg"), "system"));
        trajectoryStore.writeLlmAttempt(
                Ids.newId("att"),
                runId,
                1,
                "deepseek",
                "deepseek-reasoner",
                "SUCCEEDED",
                FinishReason.TOOL_CALLS,
                10,
                5,
                15,
                null,
                "{}"
        );
        ToolCall call = new ToolCall(
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
        );
        trajectoryStore.appendAssistantAndToolCalls(
                runId,
                LlmMessage.assistant(Ids.newId("msg"), "", List.of(new ToolCallMessage(toolUseId, "query_order", "{}"))),
                List.of(call)
        );
        trajectoryStore.writeToolResult(runId, toolUseId, ToolTerminal.succeeded(toolCallId, "{}"));

        var trajectory = trajectoryStore.loadTrajectory(runId);

        assertThat(trajectory.get("run")).isNotNull();
        assertThat((List<?>) trajectory.get("messages")).hasSize(2);
        assertThat((List<?>) trajectory.get("llmAttempts")).hasSize(1);
        assertThat((List<?>) trajectory.get("toolCalls")).hasSize(1)
                .first()
                .isInstanceOfSatisfying(AgentToolCallTraceEntity.class, savedCall -> {
                    assertThat(savedCall.getConcurrent()).isTrue();
                    assertThat(savedCall.getIdempotent()).isTrue();
                });
        assertThat((List<?>) trajectory.get("toolResults")).hasSize(1);
    }
}
