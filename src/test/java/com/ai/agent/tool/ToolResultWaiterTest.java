package com.ai.agent.tool;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.redis.RedisToolStore;
import com.ai.agent.util.Ids;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolResultWaiterTest {
    @Test
    void pubSubNotificationWakesBeforePollingIntervalAndStillReadsRedisTerminal() throws Exception {
        RedisToolStore store = mock(RedisToolStore.class);
        AgentProperties properties = new AgentProperties();
        properties.getRuntime().setToolResultPollIntervalMs(5_000);
        ToolResultPubSub pubSub = mock(ToolResultPubSub.class);
        ToolResultWaiter waiter = new ToolResultWaiter(store, properties, pubSub);
        ToolCall call = call("run-1");
        CompletableFuture<Void> notified = new CompletableFuture<>();
        AtomicBoolean terminalReady = new AtomicBoolean(false);
        ToolTerminal terminal = ToolTerminal.succeeded(call.toolCallId(), "{}");
        when(pubSub.waitFor("run-1", call.toolCallId())).thenReturn(notified);
        when(store.terminal("run-1", call.toolCallId())).thenAnswer(invocation ->
                terminalReady.get() ? Optional.of(terminal) : Optional.empty()
        );

        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            terminalReady.set(true);
            notified.complete(null);
        });
        publisher.start();

        long startedAt = System.currentTimeMillis();
        List<ToolTerminal> result = waiter.awaitResults("run-1", List.of(call), Duration.ofSeconds(2));

        assertThat(System.currentTimeMillis() - startedAt).isLessThan(1_000);
        assertThat(result).containsExactly(terminal);
        verify(pubSub).cancelWait("run-1", call.toolCallId(), notified);
        publisher.join();
    }

    @Test
    void completedNotificationDoesNotCauseBusyPollingWhenTerminalIsNotReadyYet() throws Exception {
        RedisToolStore store = mock(RedisToolStore.class);
        AgentProperties properties = new AgentProperties();
        properties.getRuntime().setToolResultPollIntervalMs(200);
        ToolResultPubSub pubSub = mock(ToolResultPubSub.class);
        ToolResultWaiter waiter = new ToolResultWaiter(store, properties, pubSub);
        ToolCall first = call("run-1", 1);
        ToolCall second = call("run-1", 2);
        CompletableFuture<Void> firstNotified = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> secondNotified = new CompletableFuture<>();
        AtomicBoolean firstTerminalReady = new AtomicBoolean(false);
        AtomicInteger firstTerminalChecks = new AtomicInteger();
        ToolTerminal firstTerminal = ToolTerminal.succeeded(first.toolCallId(), "{}");
        ToolTerminal secondTerminal = ToolTerminal.succeeded(second.toolCallId(), "{}");
        when(pubSub.waitFor("run-1", first.toolCallId())).thenReturn(firstNotified);
        when(pubSub.waitFor("run-1", second.toolCallId())).thenReturn(secondNotified);
        when(store.terminal("run-1", first.toolCallId())).thenAnswer(invocation -> {
            firstTerminalChecks.incrementAndGet();
            return firstTerminalReady.get() ? Optional.of(firstTerminal) : Optional.empty();
        });
        when(store.terminal("run-1", second.toolCallId())).thenReturn(Optional.of(secondTerminal));

        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            firstTerminalReady.set(true);
            secondNotified.complete(null);
        });
        publisher.start();

        List<ToolTerminal> result = waiter.awaitResults("run-1", List.of(first, second), Duration.ofSeconds(2));

        assertThat(result).containsExactly(firstTerminal, secondTerminal);
        assertThat(firstTerminalChecks.get()).isLessThan(10);
        publisher.join();
    }

    private ToolCall call(String runId) {
        return call(runId, 1);
    }

    private ToolCall call(String runId, long seq) {
        return new ToolCall(
                runId,
                Ids.newId("tc"),
                seq,
                Ids.newId("call"),
                "query_order",
                "query_order",
                "{}",
                true,
                true,
                false,
                null
        );
    }
}
