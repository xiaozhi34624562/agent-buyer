package com.ai.agent;

import com.ai.agent.api.RunRepairService;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.ToolCallMessage;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RunRepairServiceIntegrationTest {
    @Autowired
    TrajectoryStore trajectoryStore;

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
        assertThat((List<?>) trajectoryStore.loadTrajectory(runId).get("toolResults")).hasSize(1);
    }
}
