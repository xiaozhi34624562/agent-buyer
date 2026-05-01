package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class LargeResultSpiller {
    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;

    public LargeResultSpiller(AgentProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public List<LlmMessage> spill(String runId, List<LlmMessage> messages) {
        List<LlmMessage> view = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            if (message.role() != MessageRole.TOOL || tokenEstimator.estimate(message.content()) <= thresholdTokens()) {
                view.add(message);
                continue;
            }
            view.add(spillToolResult(runId, message));
        }
        return List.copyOf(view);
    }

    private LlmMessage spillToolResult(String runId, LlmMessage message) {
        String content = tokenEstimator.head(message.content(), headTokens())
                + "\n<resultPath>" + resultPath(runId, message.toolUseId()) + "</resultPath>\n"
                + tokenEstimator.tail(message.content(), tailTokens());
        return new LlmMessage(
                message.messageId(),
                message.role(),
                content,
                message.toolCalls(),
                message.toolUseId(),
                message.extras()
        );
    }

    private String resultPath(String runId, String toolUseId) {
        return "trajectory://runs/" + runId + "/tool-results/" + toolUseId + "/full";
    }

    private int thresholdTokens() {
        return properties.getContext().getLargeResultThresholdTokens();
    }

    private int headTokens() {
        return properties.getContext().getLargeResultHeadTokens();
    }

    private int tailTokens() {
        return properties.getContext().getLargeResultTailTokens();
    }
}
