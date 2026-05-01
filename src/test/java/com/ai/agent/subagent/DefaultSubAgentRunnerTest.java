package com.ai.agent.subagent;

import com.ai.agent.api.AgentRunResult;
import com.ai.agent.api.AgentTurnOrchestrator;
import com.ai.agent.api.ToolCallCoordinator;
import com.ai.agent.config.AgentProperties;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.trajectory.ChildRunCreation;
import com.ai.agent.trajectory.RunContext;
import com.ai.agent.trajectory.RunContextStore;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultSubAgentRunnerTest {
    @Test
    void createsIsolatedChildRunWithInheritedAllowedToolsAndReturnsSummary() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(
                eq("child-run-1"),
                eq("user-1"),
                eq("parent-run-1"),
                eq("tc-1"),
                eq("explore"),
                eq("LIVE")
        )).thenReturn(new ChildRunCreation("child-run-1", true));
        when(fixture.turnOrchestrator.runSubAgentUntilStop(
                eq("child-run-1"),
                eq("parent-run-1"),
                eq("user-1"),
                any(RunContext.class),
                eq(null),
                any()
        )).thenReturn(new AgentRunResult("child-run-1", RunStatus.SUCCEEDED, "found one candidate order"));

        SubAgentResult result = fixture.runner.run(task(), () -> false);

        assertThat(result.childRunId()).isEqualTo("child-run-1");
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.summary()).isEqualTo("found one candidate order");
        assertThat(fixture.createdChildContext.effectiveAllowedTools())
                .containsExactly("query_order", "skill_list", "skill_view");
        ArgumentCaptor<LlmMessage> messages = ArgumentCaptor.forClass(LlmMessage.class);
        verify(fixture.trajectoryStore, times(2)).appendMessage(eq("child-run-1"), messages.capture());
        assertThat(messages.getAllValues())
                .extracting(LlmMessage::content)
                .anySatisfy(content -> assertThat(content).contains("ExploreAgent"))
                .anySatisfy(content -> assertThat(content).contains("Delegated task"));
        verify(fixture.trajectoryStore).transitionRunStatus("child-run-1", RunStatus.CREATED, RunStatus.RUNNING, null);
        verify(fixture.childRunRegistry).release(
                "parent-run-1",
                "child-run-1",
                ChildReleaseReason.SUCCEEDED,
                ParentLinkStatus.LIVE
        );
        fixture.close();
    }

    @Test
    void reserveBudgetExceededFailsBeforeCreatingChildRun() {
        Fixture fixture = new Fixture();
        ReserveChildResult rejected = ReserveChildResult.rejected(
                "parent-run-1",
                "child-run-1",
                ChildReserveRejectReason.MAX_SPAWN_PER_RUN
        );
        when(fixture.childRunRegistry.reserve(any())).thenReturn(rejected);

        assertThatThrownBy(() -> fixture.runner.run(task(), () -> false))
                .isInstanceOf(SubAgentBudgetExceededException.class);
        fixture.close();
    }

    @Test
    void reserveUsesStableParentUserTurnInsteadOfLlmTurn() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.trajectoryStore.currentTurn("parent-run-1")).thenReturn(9);
        when(fixture.trajectoryReader.loadMessages("parent-run-1")).thenReturn(List.of(
                LlmMessage.system("p-s1", "system"),
                LlmMessage.user("p-u1", "first user turn"),
                LlmMessage.assistant("p-a1", "assistant", List.of()),
                LlmMessage.user("p-u2", "current user turn")
        ));
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));
        when(fixture.turnOrchestrator.runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any()))
                .thenReturn(new AgentRunResult("child-run-1", RunStatus.SUCCEEDED, "done"));

        fixture.runner.run(task(), () -> false);

        ArgumentCaptor<ReserveChildCommand> commandCaptor = ArgumentCaptor.forClass(ReserveChildCommand.class);
        verify(fixture.childRunRegistry).reserve(commandCaptor.capture());
        assertThat(commandCaptor.getValue().userTurnNo()).isEqualTo(2);
        fixture.close();
    }

    @Test
    void timeoutDetachesChildAndReturnsPartialResult() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.getSubAgent().setWaitTimeoutMs(20);
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));
        when(fixture.turnOrchestrator.runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return new AgentRunResult("child-run-1", RunStatus.SUCCEEDED, "late");
                });

        SubAgentResult result = fixture.runner.run(task(), () -> false);

        assertThat(result.status()).isEqualTo(RunStatus.TIMEOUT);
        assertThat(result.partial()).isTrue();
        verify(fixture.trajectoryStore).updateRunStatus("child-run-1", RunStatus.TIMEOUT, "subagent wait timeout");
        verify(fixture.trajectoryStore).updateParentLinkStatus("child-run-1", ParentLinkStatus.DETACHED_BY_TIMEOUT.name());
        verify(fixture.childRunRegistry).release(
                "parent-run-1",
                "child-run-1",
                ChildReleaseReason.TIMEOUT,
                ParentLinkStatus.DETACHED_BY_TIMEOUT
        );
        fixture.close();
    }

    @Test
    void reusedChildRunDoesNotReinitializeContextOrMessages() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1", true));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", false));
        when(fixture.trajectoryStore.findRunStatus("child-run-1")).thenReturn(RunStatus.SUCCEEDED);
        when(fixture.trajectoryReader.loadMessages("child-run-1")).thenReturn(List.of(
                LlmMessage.system("msg-1", "system"),
                LlmMessage.assistant("msg-2", "previous child summary", List.of())
        ));

        SubAgentResult result = fixture.runner.run(task(), () -> false);

        assertThat(result.childRunId()).isEqualTo("child-run-1");
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.summary()).isEqualTo("previous child summary");
        assertThat(result.partial()).isFalse();
        verify(fixture.runContextStore, times(0)).create(any(RunContext.class));
        verify(fixture.trajectoryStore, times(0)).appendMessage(anyString(), any(LlmMessage.class));
        verify(fixture.trajectoryStore, times(0)).transitionRunStatus(anyString(), any(), any(), any());
        verify(fixture.turnOrchestrator, times(0)).runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any());
        fixture.close();
    }

    @Test
    void executorRejectMarksChildFailedAndReleasesSlot() throws Exception {
        Fixture fixture = new Fixture(new RejectingExecutorService());
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));

        SubAgentResult result = fixture.runner.run(task(), () -> false);

        assertThat(result.childRunId()).isEqualTo("child-run-1");
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.summary()).contains("executor rejected");
        verify(fixture.trajectoryStore).updateRunStatus("child-run-1", RunStatus.FAILED, "subagent executor rejected");
        verify(fixture.trajectoryStore).updateParentLinkStatus("child-run-1", ParentLinkStatus.DETACHED_BY_PARENT_FAILED.name());
        verify(fixture.childRunRegistry).release(
                "parent-run-1",
                "child-run-1",
                ChildReleaseReason.FAILED,
                ParentLinkStatus.DETACHED_BY_PARENT_FAILED
        );
        verify(fixture.turnOrchestrator, times(0)).runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any());
        fixture.close();
    }

    @Test
    void childInitializationFailureMarksChildFailedAndReleasesSlot() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));
        doAnswer(invocation -> {
            throw new IllegalStateException("context store unavailable");
        }).when(fixture.runContextStore).create(any(RunContext.class));

        assertThatThrownBy(() -> fixture.runner.run(task(), () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("context store unavailable");

        verify(fixture.trajectoryStore).updateRunStatus("child-run-1", RunStatus.FAILED, "subagent initialization failed");
        verify(fixture.trajectoryStore).updateParentLinkStatus("child-run-1", ParentLinkStatus.DETACHED_BY_PARENT_FAILED.name());
        verify(fixture.childRunRegistry).release(
                "parent-run-1",
                "child-run-1",
                ChildReleaseReason.FAILED,
                ParentLinkStatus.DETACHED_BY_PARENT_FAILED
        );
        fixture.close();
    }

    @Test
    void childExecutionFailureReturnsFailedResultWithChildRunId() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));
        when(fixture.turnOrchestrator.runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        SubAgentResult result = fixture.runner.run(task(), () -> false);

        assertThat(result.childRunId()).isEqualTo("child-run-1");
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.partial()).isTrue();
        assertThat(result.summary()).contains("provider unavailable");
        verify(fixture.trajectoryStore).updateRunStatus("child-run-1", RunStatus.FAILED, "subagent execution failed");
        verify(fixture.childRunRegistry).release(
                "parent-run-1",
                "child-run-1",
                ChildReleaseReason.FAILED,
                ParentLinkStatus.DETACHED_BY_PARENT_FAILED
        );
        fixture.close();
    }

    @Test
    void timeoutAbortsChildToolsBeforeReturningPartialResult() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.getSubAgent().setWaitTimeoutMs(20);
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));
        when(fixture.turnOrchestrator.runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return new AgentRunResult("child-run-1", RunStatus.SUCCEEDED, "late");
                });

        SubAgentResult result = fixture.runner.run(task(), () -> false);

        assertThat(result.status()).isEqualTo(RunStatus.TIMEOUT);
        verify(fixture.toolCallCoordinator).abortRunTools(
                eq("child-run-1"),
                eq("subagent_wait_timeout"),
                any()
        );
        fixture.close();
    }

    @Test
    void parentAbortCancelsRunningChildRunAndReleasesSlot() throws Exception {
        Fixture fixture = new Fixture();
        fixture.properties.getSubAgent().setWaitTimeoutMs(1_000);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(fixture.childRunRegistry.reserve(any()))
                .thenReturn(ReserveChildResult.accepted("parent-run-1", "child-run-1"));
        when(fixture.trajectoryStore.createChildRun(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChildRunCreation("child-run-1", true));
        when(fixture.turnOrchestrator.runSubAgentUntilStop(anyString(), anyString(), anyString(), any(), eq(null), any()))
                .thenAnswer(invocation -> {
                    cancelled.set(true);
                    Thread.sleep(500);
                    return new AgentRunResult("child-run-1", RunStatus.SUCCEEDED, "late");
                });

        SubAgentResult result = fixture.runner.run(task(), cancelled::get);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.partial()).isTrue();
        verify(fixture.toolCallCoordinator).abortRunTools(
                eq("child-run-1"),
                eq("parent_run_aborted"),
                any()
        );
        verify(fixture.trajectoryStore).updateRunStatus("child-run-1", RunStatus.CANCELLED, "parent run aborted");
        verify(fixture.trajectoryStore).updateParentLinkStatus("child-run-1", ParentLinkStatus.DETACHED_BY_PARENT_FAILED.name());
        verify(fixture.childRunRegistry).release(
                "parent-run-1",
                "child-run-1",
                ChildReleaseReason.PARENT_FAILED,
                ParentLinkStatus.DETACHED_BY_PARENT_FAILED
        );
        fixture.close();
    }

    private SubAgentTask task() {
        return new SubAgentTask(
                "parent-run-1",
                "tc-1",
                "user-1",
                "explore",
                "find candidate orders",
                "stay concise",
                List.of("query_order", "cancel_order", "skill_list", "skill_view", "agent_tool")
        );
    }

    private static final class Fixture {
        private final AgentProperties properties = new AgentProperties();
        private final SubAgentRegistry subAgentRegistry = new SubAgentRegistry(List.of(new ExploreAgentProfile()));
        private final ChildRunRegistry childRunRegistry = mock(ChildRunRegistry.class);
        private final TrajectoryStore trajectoryStore = mock(TrajectoryStore.class);
        private final TrajectoryReader trajectoryReader = mock(TrajectoryReader.class);
        private final RunContextStore runContextStore = mock(RunContextStore.class);
        private final AgentTurnOrchestrator turnOrchestrator = mock(AgentTurnOrchestrator.class);
        private final ToolCallCoordinator toolCallCoordinator = mock(ToolCallCoordinator.class);
        private final ExecutorService executor;
        private final DefaultSubAgentRunner runner;
        private RunContext createdChildContext;

        private Fixture() {
            this(Executors.newSingleThreadExecutor());
        }

        private Fixture(ExecutorService executor) {
            this.executor = executor;
            when(runContextStore.load("parent-run-1")).thenReturn(parentContext());
            doAnswer(invocation -> {
                createdChildContext = invocation.getArgument(0);
                when(runContextStore.load(createdChildContext.runId())).thenReturn(createdChildContext);
                return null;
            }).when(runContextStore).create(any(RunContext.class));
            when(trajectoryStore.currentTurn("parent-run-1")).thenReturn(3);
            when(trajectoryReader.loadMessages("parent-run-1")).thenReturn(List.of(
                    LlmMessage.user("p-u1", "parent task")
            ));
            runner = new DefaultSubAgentRunner(
                    properties,
                    subAgentRegistry,
                    childRunRegistry,
                    trajectoryStore,
                    trajectoryReader,
                    runContextStore,
                    turnOrchestrator,
                    toolCallCoordinator,
                    executor
            );
        }

        private RunContext parentContext() {
            return new RunContext(
                    "parent-run-1",
                    List.of("query_order", "cancel_order", "skill_list", "skill_view", "agent_tool"),
                    "deepseek-reasoner",
                    "deepseek",
                    "qwen",
                    "{}",
                    10,
                    null,
                    null
            );
        }

        private void close() {
            executor.shutdownNow();
        }
    }

    private static final class RejectingExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("subagent queue full");
        }
    }
}
