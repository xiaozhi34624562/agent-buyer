package com.ai.agent.tool.redis;

import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface RedisToolStore {
    boolean ingestWaiting(String runId, ToolCall call);

    List<StartedTool> schedule(String runId);

    boolean complete(StartedTool running, ToolTerminal terminal);

    List<ToolTerminal> reapExpiredLeases(String runId, long nowMillis);

    List<ToolTerminal> cancelWaiting(String runId, CancelReason reason);

    Optional<ToolTerminal> terminal(String runId, String toolCallId);

    Set<String> activeRunIds();

    default void removeActiveRun(String runId) {
    }

    List<ToolTerminal> abort(String runId, String reason);

    default List<ToolTerminal> interrupt(String runId, String reason) {
        return List.of();
    }

    boolean abortRequested(String runId);

    default boolean interruptRequested(String runId) {
        return false;
    }

    default void clearInterrupt(String runId) {
    }
}
