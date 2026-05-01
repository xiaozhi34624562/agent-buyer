package com.ai.agent.api;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.subagent.ChildReleaseReason;
import com.ai.agent.subagent.ChildRunRef;
import com.ai.agent.subagent.ChildRunRegistry;
import com.ai.agent.subagent.ChildRunState;
import com.ai.agent.subagent.ParentLinkStatus;
import com.ai.agent.tool.RunEventSinkRegistry;
import com.ai.agent.tool.ToolResultCloser;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunInterruptServiceTest {
    @Test
    void interruptPausesRunCancelsWaitingToolsAndCascadesToActiveChildren() {
        AgentProperties properties = new AgentProperties();
        RunAccessManager accessManager = mock(RunAccessManager.class);
        RunStateMachine stateMachine = mock(RunStateMachine.class);
        RedisToolStore redisToolStore = mock(RedisToolStore.class);
        ToolResultCloser closer = mock(ToolResultCloser.class);
        RunEventSinkRegistry sinkRegistry = new RunEventSinkRegistry();
        ChildRunRegistry childRunRegistry = mock(ChildRunRegistry.class);
        TrajectoryStore trajectoryStore = mock(TrajectoryStore.class);
        RunInterruptService service = new RunInterruptService(
                properties,
                accessManager,
                stateMachine,
                redisToolStore,
                closer,
                sinkRegistry,
                childRunRegistry,
                trajectoryStore
        );
        ToolTerminal terminal = ToolTerminal.syntheticCancelled(
                "tc-1",
                com.ai.agent.tool.CancelReason.INTERRUPTED,
                "{\"type\":\"interrupted\"}"
        );
        when(redisToolStore.interrupt("run-1", "user_interrupt")).thenReturn(List.of(terminal));
        when(trajectoryStore.findRunStatus("run-1")).thenReturn(RunStatus.RUNNING);
        when(stateMachine.interruptIfActive("run-1", "user_interrupt"))
                .thenReturn(new RunStateMachine.TransitionResult(RunStatus.PAUSED, true));
        when(childRunRegistry.findActiveChildren("run-1")).thenReturn(List.of(new ChildRunRef(
                "run-1",
                "child-run-1",
                "tc-parent",
                "explore",
                1,
                ChildRunState.IN_FLIGHT,
                ParentLinkStatus.LIVE,
                1L,
                null,
                null
        )));

        RunInterruptService.InterruptRunResponse response = service.interrupt("user-1", "run-1");

        assertThat(response.status()).isEqualTo(RunStatus.PAUSED);
        assertThat(response.changed()).isTrue();
        assertThat(response.nextActionRequired()).isEqualTo("user_input");
        assertThat(response.interruptedChildren()).isEqualTo(1);
        verify(accessManager).assertOwner("run-1", "user-1");
        verify(redisToolStore).interrupt("run-1", "user_interrupt");
        verify(closer).closeTerminals("run-1", List.of(terminal), null);
        verify(redisToolStore).interrupt("child-run-1", "parent_interrupted");
        verify(trajectoryStore).updateRunStatus("child-run-1", RunStatus.PAUSED, "parent interrupted");
        verify(trajectoryStore).updateParentLinkStatus("child-run-1", ParentLinkStatus.DETACHED_BY_INTERRUPT.name());
        verify(childRunRegistry).release(
                "run-1",
                "child-run-1",
                ChildReleaseReason.INTERRUPTED,
                ParentLinkStatus.DETACHED_BY_INTERRUPT
        );
    }

    @Test
    void interruptTerminalRunDoesNotSetControlOrAskForContinuation() {
        AgentProperties properties = new AgentProperties();
        RunAccessManager accessManager = mock(RunAccessManager.class);
        RunStateMachine stateMachine = mock(RunStateMachine.class);
        RedisToolStore redisToolStore = mock(RedisToolStore.class);
        ToolResultCloser closer = mock(ToolResultCloser.class);
        ChildRunRegistry childRunRegistry = mock(ChildRunRegistry.class);
        TrajectoryStore trajectoryStore = mock(TrajectoryStore.class);
        RunInterruptService service = new RunInterruptService(
                properties,
                accessManager,
                stateMachine,
                redisToolStore,
                closer,
                new RunEventSinkRegistry(),
                childRunRegistry,
                trajectoryStore
        );
        when(trajectoryStore.findRunStatus("run-done")).thenReturn(RunStatus.SUCCEEDED);

        RunInterruptService.InterruptRunResponse response = service.interrupt("user-1", "run-done");

        assertThat(response.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(response.changed()).isFalse();
        assertThat(response.nextActionRequired()).isNull();
        assertThat(response.interruptedChildren()).isZero();
        verify(accessManager).assertOwner("run-done", "user-1");
        verify(redisToolStore, never()).interrupt("run-done", "user_interrupt");
        verify(stateMachine, never()).interruptIfActive("run-done", "user_interrupt");
    }
}
