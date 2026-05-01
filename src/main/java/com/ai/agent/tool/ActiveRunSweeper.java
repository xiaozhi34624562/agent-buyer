package com.ai.agent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.tool.redis.RedisToolStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public final class ActiveRunSweeper {
    private static final Logger log = LoggerFactory.getLogger(ActiveRunSweeper.class);

    private final AgentProperties properties;
    private final RedisToolStore store;
    private final ToolExecutionLauncher launcher;
    private final AgentRunMapper runMapper;

    public ActiveRunSweeper(
            AgentProperties properties,
            RedisToolStore store,
            ToolExecutionLauncher launcher,
            AgentRunMapper runMapper
    ) {
        this.properties = properties;
        this.store = store;
        this.launcher = launcher;
        this.runMapper = runMapper;
    }

    @Scheduled(fixedDelayString = "${agent.runtime.active-run-sweeper-interval-ms:2000}")
    public void sweep() {
        if (!properties.getRuntime().isActiveRunSweeperEnabled()) {
            return;
        }
        for (String runId : store.activeRunIds()) {
            if (cleanupTerminalRun(runId)) {
                continue;
            }
            List<StartedTool> started = store.schedule(runId);
            launcher.launchScheduled(runId, started);
        }
    }

    private boolean cleanupTerminalRun(String runId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            store.removeActiveRun(runId);
            return true;
        }
        RunStatus status = RunStatus.valueOf(run.getStatus());
        if (!isTerminal(status)) {
            return false;
        }
        LocalDateTime completedAt = run.getCompletedAt();
        if (completedAt == null) {
            return false;
        }
        LocalDateTime cutoff = LocalDateTime.now()
                .minus(Duration.ofMillis(properties.getRuntime().getActiveRunStaleCleanupMs()));
        if (completedAt.isBefore(cutoff)) {
            store.removeActiveRun(runId);
            log.info("active run stale cleanup removed terminal run runId={} status={}", runId, status);
            return true;
        }
        return false;
    }

    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.FAILED_RECOVERED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMEOUT;
    }
}
