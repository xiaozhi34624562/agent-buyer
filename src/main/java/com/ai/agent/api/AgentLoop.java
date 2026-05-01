package com.ai.agent.api;

public interface AgentLoop {
    AgentRunResult run(String userId, AgentRunRequest request, AgentEventSink sink);

    AgentRunResult continueRun(String userId, String runId, UserMessage message, AgentEventSink sink);

    AgentRunResult continueRun(
            String userId,
            String runId,
            UserMessage message,
            AgentEventSink sink,
            RunAccessManager.ContinuationPermit permit
    );
}
