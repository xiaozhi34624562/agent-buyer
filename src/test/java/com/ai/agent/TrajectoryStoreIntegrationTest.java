package com.ai.agent;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryReader;
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

    @Autowired
    TrajectoryReader trajectoryReader;

    @Autowired
    RunContextStore runContextStore;

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

        var trajectory = trajectoryReader.loadTrajectorySnapshot(runId);

        assertThat(trajectory.run()).isNotNull();
        assertThat(trajectory.messages()).hasSize(2);
        assertThat(trajectory.llmAttempts()).hasSize(1);
        assertThat(trajectory.toolCalls()).hasSize(1)
                .first()
                .satisfies(savedCall -> {
                    assertThat(savedCall.getConcurrent()).isTrue();
                    assertThat(savedCall.getIdempotent()).isTrue();
                });
        assertThat(trajectory.toolResults()).hasSize(1);
    }

    @Test
    void runContextPersistsEffectiveToolsModelAndMaxTurns() {
        String runId = Ids.newId("test_run");

        trajectoryStore.createRun(runId, "demo-user");
        runContextStore.create(new RunContext(
                runId,
                List.of("cancel_order", "query_order"),
                "deepseek-chat",
                "deepseek",
                "qwen",
                "{}",
                3,
                null,
                null
        ));

        RunContext loaded = runContextStore.load(runId);

        assertThat(loaded.runId()).isEqualTo(runId);
        assertThat(loaded.effectiveAllowedTools()).containsExactly("cancel_order", "query_order");
        assertThat(loaded.model()).isEqualTo("deepseek-chat");
        assertThat(loaded.primaryProvider()).isEqualTo("deepseek");
        assertThat(loaded.fallbackProvider()).isEqualTo("qwen");
        assertThat(loaded.providerOptions()).isEqualTo("{}");
        assertThat(loaded.maxTurns()).isEqualTo(3);
        assertThat(loaded.createdAt()).isNotNull();
        assertThat(loaded.updatedAt()).isNotNull();
    }
}
