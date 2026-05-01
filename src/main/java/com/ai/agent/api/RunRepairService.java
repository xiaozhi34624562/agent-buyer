package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.persistence.mapper.AgentToolCallTraceMapper;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.ConfirmTokenStore;
import com.ai.agent.tool.ToolStatus;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public final class RunRepairService {
    private static final int REPAIR_BATCH_SIZE = 100;

    private final AgentProperties properties;
    private final AgentRunMapper runMapper;
    private final AgentToolCallTraceMapper toolCallMapper;
    private final TrajectoryStore trajectoryStore;
    private final ConfirmTokenStore confirmTokenStore;

    public RunRepairService(
            AgentProperties properties,
            AgentRunMapper runMapper,
            AgentToolCallTraceMapper toolCallMapper,
            TrajectoryStore trajectoryStore,
            ConfirmTokenStore confirmTokenStore
    ) {
        this.properties = properties;
        this.runMapper = runMapper;
        this.toolCallMapper = toolCallMapper;
        this.trajectoryStore = trajectoryStore;
        this.confirmTokenStore = confirmTokenStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void repairStartupOrphans() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusNanos(properties.getAgentLoop().getToolResultTimeoutMs() * 1_000_000L);
        for (AgentRunEntity run : runMapper.findStartupRepairCandidates(cutoff, REPAIR_BATCH_SIZE)) {
            int repaired = 0;
            for (AgentToolCallTraceEntity call : toolCallMapper.findMissingResultsByRunId(run.getRunId())) {
                ToolTerminal terminal = ToolTerminal.syntheticCancelled(
                        call.getToolCallId(),
                        CancelReason.RUN_ABORTED,
                        "{\"type\":\"startup_repair\",\"message\":\"tool result was missing after restart\"}"
                );
                trajectoryStore.writeToolResult(run.getRunId(), call.getToolUseId(), terminal);
                trajectoryStore.appendMessage(
                        run.getRunId(),
                        LlmMessage.tool(Ids.newId("msg"), call.getToolUseId(), terminal.errorJson())
                );
                repaired++;
            }
            RunStatus status = repaired > 0 ? RunStatus.FAILED_RECOVERED : RunStatus.TIMEOUT;
            String message = repaired > 0
                    ? "startup repair closed missing tool results"
                    : "startup repair closed stale run";
            trajectoryStore.updateRunStatus(run.getRunId(), status, message);
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void expireWaitingConfirmations() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getConfirmationTtl());
        for (AgentRunEntity run : runMapper.findExpiredConfirmationRuns(cutoff, REPAIR_BATCH_SIZE)) {
            confirmTokenStore.clearRun(run.getRunId());
            trajectoryStore.appendMessage(
                    run.getRunId(),
                    LlmMessage.assistant(Ids.newId("msg"), "确认已超时，写操作未执行。", java.util.List.of())
            );
            trajectoryStore.updateRunStatus(run.getRunId(), RunStatus.TIMEOUT, "confirmation timeout");
        }
    }

    public void repairNowForTests() {
        repairStartupOrphans();
    }
}
