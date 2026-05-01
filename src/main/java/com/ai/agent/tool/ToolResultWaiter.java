package com.ai.agent.tool;

import com.ai.agent.tool.redis.RedisToolStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public final class ToolResultWaiter {
    private final RedisToolStore store;

    public ToolResultWaiter(RedisToolStore store) {
        this.store = store;
    }

    public List<ToolTerminal> awaitResults(String runId, List<ToolCall> calls, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        List<ToolTerminal> terminals = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            terminals.clear();
            boolean allDone = true;
            for (ToolCall call : calls) {
                var terminal = store.terminal(runId, call.toolCallId());
                if (terminal.isEmpty()) {
                    allDone = false;
                    break;
                }
                terminals.add(terminal.get());
            }
            if (allDone) {
                return List.copyOf(terminals);
            }
            sleep();
        }
        throw new IllegalStateException("tool result timeout");
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting tool result", e);
        }
    }
}
