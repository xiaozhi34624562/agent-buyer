package com.ai.agent.tool.runtime;

import com.ai.agent.web.sse.AgentEventSink;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class RunEventSinkRegistry {
    private final ConcurrentMap<String, AgentEventSink> sinks = new ConcurrentHashMap<>();

    public void bind(String runId, AgentEventSink sink) {
        sinks.put(runId, sink);
    }

    public void unbind(String runId) {
        sinks.remove(runId);
    }

    public Optional<AgentEventSink> find(String runId) {
        return Optional.ofNullable(sinks.get(runId));
    }
}
