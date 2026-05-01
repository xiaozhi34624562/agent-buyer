package com.ai.agent.tool;

import com.ai.agent.api.AgentEventSink;
import com.ai.agent.api.ToolResultEvent;
import com.ai.agent.llm.LlmMessage;
import com.ai.agent.llm.MessageRole;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.trajectory.TrajectoryReader;
import com.ai.agent.trajectory.TrajectorySnapshot;
import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class ToolResultCloser {
    private static final Logger log = LoggerFactory.getLogger(ToolResultCloser.class);
    private static final int CLOSE_LOCK_STRIPES = 1024;

    private final TrajectoryStore trajectoryStore;
    private final TrajectoryReader trajectoryReader;
    private final ToolResultPubSub pubSub;
    private final Object[] closeLocks = createCloseLocks();

    @Autowired
    public ToolResultCloser(
            TrajectoryStore trajectoryStore,
            TrajectoryReader trajectoryReader,
            ObjectProvider<ToolResultPubSub> pubSubProvider
    ) {
        this(trajectoryStore, trajectoryReader, pubSubProvider == null ? null : pubSubProvider.getIfAvailable());
    }

    private ToolResultCloser(TrajectoryStore trajectoryStore, TrajectoryReader trajectoryReader, ToolResultPubSub pubSub) {
        this.trajectoryStore = trajectoryStore;
        this.trajectoryReader = trajectoryReader;
        this.pubSub = pubSub;
    }

    public void closeTerminal(String runId, ToolCall call, ToolTerminal terminal, AgentEventSink sink) {
        closeTerminalWithFreshState(runId, call, terminal, sink);
    }

    public void closeTerminals(String runId, List<ToolTerminal> terminals, AgentEventSink sink) {
        if (terminals == null || terminals.isEmpty()) {
            return;
        }
        Map<String, ToolCall> callsById = trajectoryReader.findToolCallsByRun(runId).stream()
                .collect(Collectors.toMap(ToolCall::toolCallId, call -> call, (left, right) -> left, LinkedHashMap::new));
        for (ToolTerminal terminal : terminals) {
            ToolCall call = callsById.get(terminal.toolCallId());
            if (call == null) {
                log.warn("tool terminal cannot be closed because tool call is missing toolCallId={}", terminal.toolCallId());
                continue;
            }
            closeTerminalWithFreshState(runId, call, terminal, sink);
        }
    }

    public List<ToolTerminal> closeSynthetic(
            String runId,
            List<ToolCall> calls,
            CancelReason reason,
            String errorJson,
            AgentEventSink sink
    ) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        List<ToolTerminal> terminals = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            ToolTerminal terminal = ToolTerminal.syntheticCancelled(call.toolCallId(), reason, errorJson);
            closeTerminalWithFreshState(runId, call, terminal, sink);
            terminals.add(terminal);
        }
        return terminals;
    }

    private void closeTerminalWithFreshState(
            String runId,
            ToolCall call,
            ToolTerminal terminal,
            AgentEventSink sink
    ) {
        synchronized (closeLock(runId, call.toolUseId())) {
            ClosedState closed = loadClosedState(runId);
            closeTerminal(runId, call, terminal, sink, closed);
        }
    }

    private void closeTerminal(
            String runId,
            ToolCall call,
            ToolTerminal terminal,
            AgentEventSink sink,
            ClosedState closed
    ) {
        boolean wrote = false;
        if (!closed.resultToolCallIds().contains(call.toolCallId())) {
            trajectoryStore.writeToolResult(runId, call.toolUseId(), terminal);
            closed.resultToolCallIds().add(call.toolCallId());
            wrote = true;
        }
        if (!closed.toolMessageUseIds().contains(call.toolUseId())) {
            trajectoryStore.appendMessage(runId, LlmMessage.tool(Ids.newId("msg"), call.toolUseId(), terminalContent(terminal)));
            closed.toolMessageUseIds().add(call.toolUseId());
            wrote = true;
        }
        if (wrote && sink != null) {
            sink.onToolResult(new ToolResultEvent(
                    runId,
                    call.toolUseId(),
                    terminal.status(),
                    terminal.resultJson(),
                    terminal.errorJson()
            ));
        }
        if (wrote && pubSub != null) {
            pubSub.publish(runId, call.toolCallId());
        }
    }

    private ClosedState loadClosedState(String runId) {
        try {
            TrajectorySnapshot snapshot = trajectoryReader.loadTrajectorySnapshot(runId);
            Set<String> resultToolCallIds = snapshot.toolResults().stream()
                    .map(AgentToolResultTraceEntity::getToolCallId)
                    .collect(Collectors.toSet());
            Set<String> toolMessageUseIds = snapshot.messages().stream()
                    .filter(message -> MessageRole.TOOL.name().equals(message.getRole()))
                    .map(AgentMessageEntity::getToolUseId)
                    .collect(Collectors.toSet());
            return new ClosedState(resultToolCallIds, toolMessageUseIds);
        } catch (UnsupportedOperationException e) {
            return new ClosedState(new java.util.HashSet<>(), new java.util.HashSet<>());
        }
    }

    private Object closeLock(String runId, String toolUseId) {
        String key = runId + ":" + toolUseId;
        return closeLocks[Math.floorMod(key.hashCode(), closeLocks.length)];
    }

    private static Object[] createCloseLocks() {
        Object[] locks = new Object[CLOSE_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private String terminalContent(ToolTerminal terminal) {
        if (terminal.resultJson() != null && !terminal.resultJson().isBlank()) {
            return terminal.resultJson();
        }
        if (terminal.errorJson() != null && !terminal.errorJson().isBlank()) {
            return terminal.errorJson();
        }
        return "{\"type\":\"empty_tool_result\"}";
    }

    private record ClosedState(Set<String> resultToolCallIds, Set<String> toolMessageUseIds) {
    }
}
