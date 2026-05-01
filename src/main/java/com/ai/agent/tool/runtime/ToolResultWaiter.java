package com.ai.agent.tool.runtime;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.tool.model.ToolCall;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.runtime.redis.RedisToolStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class ToolResultWaiter {
    private final RedisToolStore store;
    private final AgentProperties properties;
    private final ToolResultPubSub pubSub;

    @Autowired
    public ToolResultWaiter(
            RedisToolStore store,
            AgentProperties properties,
            ObjectProvider<ToolResultPubSub> pubSubProvider
    ) {
        this(store, properties, pubSubProvider == null ? null : pubSubProvider.getIfAvailable());
    }

    private ToolResultWaiter(RedisToolStore store, AgentProperties properties, ToolResultPubSub pubSub) {
        this.store = store;
        this.properties = properties == null ? new AgentProperties() : properties;
        this.pubSub = pubSub;
    }

    public List<ToolTerminal> awaitResults(String runId, List<ToolCall> calls, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<ToolTerminal> terminals = new ArrayList<>();
        Map<ToolCall, CompletableFuture<Void>> notifications = registerNotifications(runId, calls);
        try {
            while (Instant.now().isBefore(deadline)) {
                terminals.clear();
                boolean allDone = true;
                List<CompletableFuture<Void>> pendingNotifications = new ArrayList<>();
                for (ToolCall call : calls) {
                    var terminal = store.terminal(runId, call.toolCallId());
                    if (terminal.isEmpty()) {
                        allDone = false;
                        CompletableFuture<Void> notification = notifications.get(call);
                        if (notification != null && !notification.isDone()) {
                            pendingNotifications.add(notification);
                        }
                        break;
                    }
                    terminals.add(terminal.get());
                }
                if (allDone) {
                    return List.copyOf(terminals);
                }
                awaitNotificationOrPollInterval(pendingNotifications, deadline);
            }
            throw new IllegalStateException("tool result timeout");
        } finally {
            cleanupNotifications(runId, notifications);
        }
    }

    private Map<ToolCall, CompletableFuture<Void>> registerNotifications(String runId, List<ToolCall> calls) {
        if (pubSub == null || !properties.getRuntime().isToolResultPubsubEnabled()) {
            return Map.of();
        }
        Map<ToolCall, CompletableFuture<Void>> futures = new LinkedHashMap<>();
        for (ToolCall call : calls) {
            futures.put(call, pubSub.waitFor(runId, call.toolCallId()));
        }
        return futures;
    }

    private void cleanupNotifications(String runId, Map<ToolCall, CompletableFuture<Void>> notifications) {
        if (pubSub == null || notifications == null || notifications.isEmpty()) {
            return;
        }
        for (Map.Entry<ToolCall, CompletableFuture<Void>> entry : notifications.entrySet()) {
            pubSub.cancelWait(runId, entry.getKey().toolCallId(), entry.getValue());
        }
    }

    private void awaitNotificationOrPollInterval(List<CompletableFuture<Void>> notifications, Instant deadline) {
        long remainingMs = Math.max(1L, Duration.between(Instant.now(), deadline).toMillis());
        long waitMs = Math.min(Math.max(1L, properties.getRuntime().getToolResultPollIntervalMs()), remainingMs);
        if (notifications != null && !notifications.isEmpty()) {
            try {
                CompletableFuture.anyOf(notifications.toArray(CompletableFuture[]::new)).get(waitMs, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException ignored) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting tool result notification", e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("tool result notification failed", e);
            }
        }
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting tool result", e);
        }
    }
}
