package com.ai.agent.tool.redis;

import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolCall;
import com.ai.agent.tool.ToolTerminal;

import java.util.List;
import java.util.Optional;

public interface RedisToolStore {
    boolean ingestWaiting(String runId, ToolCall call);

    List<StartedTool> schedule(String runId);

    boolean complete(StartedTool running, ToolTerminal terminal);

    List<ToolTerminal> cancelWaiting(String runId, CancelReason reason);

    Optional<ToolTerminal> terminal(String runId, String toolCallId);

    void abort(String runId, String reason);
}
