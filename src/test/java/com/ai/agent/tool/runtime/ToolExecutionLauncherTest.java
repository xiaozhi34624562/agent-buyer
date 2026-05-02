package com.ai.agent.tool.runtime;

import com.ai.agent.tool.core.Tool;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.registry.ToolRegistry;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import com.ai.agent.trajectory.port.TrajectoryStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionLauncherTest {
    @Test
    void launcherCompletesRedisAndDoesNotWriteTrajectoryDirectly() {
        RecordingRedisToolStore store = new RecordingRedisToolStore();
        ToolCall call = toolCall();
        StartedTool started = new StartedTool(call, 1, "lease", System.currentTimeMillis() + 60_000, "worker-1");
        TrajectoryStore trajectoryStore = mock(TrajectoryStore.class);
        ToolResultPubSub pubSub = mock(ToolResultPubSub.class);
        when(trajectoryStore.findRunUserId("run-1")).thenReturn("demo-user");
        ToolExecutionLauncher launcher = new ToolExecutionLauncher(
                store,
                new ToolRegistry(List.of(successTool())),
                trajectoryStore,
                pubSub,
                new RunEventSinkRegistry(),
                new DirectExecutorService()
        );

        launcher.launchScheduled("run-1", List.of(started));

        assertThat(store.completed).isNotNull();
        assertThat(store.completed.status()).isEqualTo(com.ai.agent.tool.model.ToolStatus.SUCCEEDED);
        verify(pubSub).publish("run-1", "tc-1");
        verify(trajectoryStore, never()).writeToolResult(anyString(), anyString(), any());
        verify(trajectoryStore, never()).appendMessage(anyString(), any());
    }

    @Test
    void pubSubFailureDoesNotFailCompletedToolExecution() {
        RecordingRedisToolStore store = new RecordingRedisToolStore();
        ToolCall call = toolCall();
        StartedTool started = new StartedTool(call, 1, "lease", System.currentTimeMillis() + 60_000, "worker-1");
        TrajectoryStore trajectoryStore = mock(TrajectoryStore.class);
        ToolResultPubSub pubSub = mock(ToolResultPubSub.class);
        doThrow(new IllegalStateException("redis publish failed")).when(pubSub).publish("run-1", "tc-1");
        when(trajectoryStore.findRunUserId("run-1")).thenReturn("demo-user");
        ToolExecutionLauncher launcher = new ToolExecutionLauncher(
                store,
                new ToolRegistry(List.of(successTool())),
                trajectoryStore,
                pubSub,
                new RunEventSinkRegistry(),
                new DirectExecutorService()
        );

        launcher.launchScheduled("run-1", List.of(started));

        assertThat(store.completed).isNotNull();
        assertThat(store.completed.status()).isEqualTo(com.ai.agent.tool.model.ToolStatus.SUCCEEDED);
    }

    private static Tool successTool() {
        return new Tool() {
            @Override
            public ToolSchema schema() {
                return new ToolSchema(
                        "query_order",
                        "query orders",
                        "{}",
                        true,
                        true,
                        Duration.ofSeconds(5),
                        4096,
                        List.of()
                );
            }

            @Override
            public ToolTerminal run(ToolExecutionContext ctx, StartedTool running, com.ai.agent.tool.core.CancellationToken token) {
                return ToolTerminal.succeeded(running.call().toolCallId(), "{\"ok\":true}");
            }
        };
    }

    private static ToolCall toolCall() {
        return new ToolCall(
                "run-1",
                "tc-1",
                1,
                "call-1",
                "query_order",
                "query_order",
                "{}",
                true,
                true,
                false,
                null
        );
    }

    private static final class RecordingRedisToolStore implements RedisToolStore {
        private ToolTerminal completed;

        @Override
        public boolean ingestWaiting(String runId, ToolCall call) {
            return true;
        }

        @Override
        public List<StartedTool> schedule(String runId) {
            return List.of();
        }

        @Override
        public boolean complete(StartedTool running, ToolTerminal terminal) {
            completed = terminal;
            return true;
        }

        @Override
        public List<ToolTerminal> reapExpiredLeases(String runId, long nowMillis) {
            return List.of();
        }

        @Override
        public List<ToolTerminal> cancelWaiting(String runId, CancelReason reason) {
            return List.of();
        }

        @Override
        public Optional<ToolTerminal> terminal(String runId, String toolCallId) {
            return Optional.ofNullable(completed);
        }

        @Override
        public Set<String> activeRunIds() {
            return Set.of();
        }

        @Override
        public List<ToolTerminal> abort(String runId, String reason) {
            return List.of();
        }

        @Override
        public boolean abortRequested(String runId) {
            return false;
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
