package com.ai.agent.trajectory.store;

import com.ai.agent.domain.FinishReason;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.trajectory.model.RunContext;
import com.ai.agent.trajectory.port.RunContextStore;
import com.ai.agent.trajectory.model.ContextCompactionRecord;
import com.ai.agent.trajectory.port.ContextCompactionStore;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.ai.agent.trajectory.port.TrajectoryStore;
import com.ai.agent.util.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TrajectoryStoreIntegrationTest {
    @Autowired
    TrajectoryStore trajectoryStore;

    @Autowired
    TrajectoryReader trajectoryReader;

    @Autowired
    RunContextStore runContextStore;

    @Autowired
    ContextCompactionStore contextCompactionStore;

    @Autowired
    AgentRunMapper runMapper;

    @Test
    void loadTrajectoryReturnsRunMessagesAttemptsToolCallsResultsAndCompactions() {
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
        contextCompactionStore.record(new ContextCompactionRecord(
                null,
                runId,
                2,
                "att-compact-1",
                "summary",
                1000,
                450,
                List.of("msg-old-1", "msg-old-2"),
                null
        ));

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
        assertThat(trajectory.compactions()).hasSize(1)
                .first()
                .satisfies(compaction -> {
                    assertThat(compaction.getRunId()).isEqualTo(runId);
                    assertThat(compaction.getTurnNo()).isEqualTo(2);
                    assertThat(compaction.getAttemptId()).isEqualTo("att-compact-1");
                    assertThat(compaction.getStrategy()).isEqualTo("summary");
                    assertThat(compaction.getBeforeTokens()).isEqualTo(1000);
                    assertThat(compaction.getAfterTokens()).isEqualTo(450);
                    assertThat(compaction.getCompactedMessageIds()).contains("msg-old-1", "msg-old-2");
                    assertThat(compaction.getCreatedAt()).isNotNull();
                });
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

    @Test
    void childRunPersistsParentLinkMetadata() {
        String parentRunId = Ids.newId("parent_run");
        String childRunId = Ids.newId("child_run");

        trajectoryStore.createRun(parentRunId, "demo-user");
        var created = trajectoryStore.createChildRun(
                childRunId,
                "demo-user",
                parentRunId,
                "parent-tool-call-1",
                "explore",
                "LIVE"
        );

        var child = trajectoryReader.loadTrajectorySnapshot(childRunId).run();

        assertThat(created.childRunId()).isEqualTo(childRunId);
        assertThat(created.created()).isTrue();
        assertThat(child.getRunId()).isEqualTo(childRunId);
        assertThat(child.getParentRunId()).isEqualTo(parentRunId);
        assertThat(child.getParentToolCallId()).isEqualTo("parent-tool-call-1");
        assertThat(child.getAgentType()).isEqualTo("explore");
        assertThat(child.getParentLinkStatus()).isEqualTo("LIVE");
    }

    @Test
    void childRunCreationReusesExistingParentToolCallAndLiveQueryFiltersDetachedChildren() {
        String parentRunId = Ids.newId("parent_run");
        String firstChildRunId = Ids.newId("child_run");
        String secondChildRunId = Ids.newId("child_run");
        String parentToolCallId = Ids.newId("parent_tc");

        trajectoryStore.createRun(parentRunId, "demo-user");
        var created = trajectoryStore.createChildRun(
                firstChildRunId,
                "demo-user",
                parentRunId,
                parentToolCallId,
                "explore",
                "LIVE"
        );
        var reused = trajectoryStore.createChildRun(
                secondChildRunId,
                "demo-user",
                parentRunId,
                parentToolCallId,
                "explore",
                "LIVE"
        );

        assertThat(created.childRunId()).isEqualTo(firstChildRunId);
        assertThat(created.created()).isTrue();
        assertThat(reused.childRunId()).isEqualTo(firstChildRunId);
        assertThat(reused.created()).isFalse();
        assertThat(runMapper.findLiveChildren(parentRunId))
                .extracting(com.ai.agent.persistence.entity.AgentRunEntity::getRunId)
                .containsExactly(firstChildRunId);

        runMapper.updateParentLinkStatus(firstChildRunId, "DETACHED_BY_TIMEOUT");

        assertThat(runMapper.findLiveChildren(parentRunId)).isEmpty();
    }

    @Test
    void childRunRejectsUnknownAgentTypeBeforePersisting() {
        String parentRunId = Ids.newId("parent_run");
        String childRunId = Ids.newId("child_run");

        trajectoryStore.createRun(parentRunId, "demo-user");

        assertThatThrownBy(() -> trajectoryStore.createChildRun(
                childRunId,
                "demo-user",
                parentRunId,
                Ids.newId("parent_tc"),
                "writer",
                "LIVE"
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(runMapper.selectById(childRunId)).isNull();
    }
}
