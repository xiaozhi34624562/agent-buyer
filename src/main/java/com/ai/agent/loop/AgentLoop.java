package com.ai.agent.loop;

import com.ai.agent.application.RunAccessManager;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.dto.UserMessage;
import com.ai.agent.web.sse.AgentEventSink;

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
