package com.ai.agent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.mapper.AgentRunMapper;
import com.ai.agent.tool.redis.RedisToolStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveRunSweeperTest {
    @Test
    void sweeperOnlySchedulesAndLaunchesExistingWaitingTools() {
        AgentProperties properties = new AgentProperties();
        RedisToolStore store = mock(RedisToolStore.class);
        ToolExecutionLauncher launcher = mock(ToolExecutionLauncher.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        ActiveRunSweeper sweeper = new ActiveRunSweeper(properties, store, launcher, runMapper);
        StartedTool started = mock(StartedTool.class);
        when(store.activeRunIds()).thenReturn(Set.of("run-1"));
        when(runMapper.selectById("run-1")).thenReturn(run("run-1", RunStatus.RUNNING, null));
        when(store.schedule("run-1")).thenReturn(List.of(started));

        sweeper.sweep();

        verify(store).schedule("run-1");
        verify(launcher).launchScheduled("run-1", List.of(started));
    }

    @Test
    void staleTerminalRunIsRemovedFromActiveSetWithoutScheduling() {
        AgentProperties properties = new AgentProperties();
        RedisToolStore store = mock(RedisToolStore.class);
        ToolExecutionLauncher launcher = mock(ToolExecutionLauncher.class);
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        ActiveRunSweeper sweeper = new ActiveRunSweeper(properties, store, launcher, runMapper);
        when(store.activeRunIds()).thenReturn(Set.of("run-1"));
        when(runMapper.selectById("run-1")).thenReturn(run(
                "run-1",
                RunStatus.SUCCEEDED,
                LocalDateTime.now().minusSeconds(120)
        ));

        sweeper.sweep();

        verify(store).removeActiveRun("run-1");
        verify(store, never()).schedule("run-1");
        verify(launcher, never()).launchScheduled(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    private AgentRunEntity run(String runId, RunStatus status, LocalDateTime completedAt) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setStatus(status.name());
        entity.setCompletedAt(completedAt);
        return entity;
    }
}
