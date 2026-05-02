package com.ai.agent.tool.runtime;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 活跃运行清扫器，定期扫描并调度活跃运行中的待执行工具。
 *
 * <p>通过定时任务检查活跃运行队列，触发待执行工具的调度，
 * 并清理已完成或过期的活跃运行记录。
 */
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

    /**
     * 定时扫描活跃运行并调度执行。
     *
     * <p>检查所有活跃运行，清理已完成的运行，调度待执行的工具。
     */
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

    /**
     * 清理已终止的运行。
     *
     * @param runId 运行标识符
     * @return 如果运行已终止并清理成功则返回true，否则返回false
     */
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

    /**
     * 检查运行状态是否为终止状态。
     *
     * @param status 运行状态
     * @return 如果为终止状态则返回true，否则返回false
     */
    private boolean isTerminal(RunStatus status) {
        return status == RunStatus.SUCCEEDED
                || status == RunStatus.FAILED
                || status == RunStatus.FAILED_RECOVERED
                || status == RunStatus.CANCELLED
                || status == RunStatus.TIMEOUT;
    }
}
