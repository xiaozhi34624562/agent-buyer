package com.ai.agent.llm;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class MicroCompactor {
    public static final String OLD_TOOL_RESULT_PLACEHOLDER =
            "<oldToolResult>Tool result is deleted due to long context</oldToolResult>";

    private static final int RECENT_MESSAGE_WINDOW = 3;

    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;

    public MicroCompactor(AgentProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public List<LlmMessage> compact(List<LlmMessage> messages) {
        if (totalTokens(messages) < thresholdTokens()) {
            return List.copyOf(messages);
        }
        int protectedFromIndex = Math.max(0, messages.size() - RECENT_MESSAGE_WINDOW);
        List<LlmMessage> view = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage message = messages.get(i);
            if (i < protectedFromIndex && message.role() == MessageRole.TOOL) {
                view.add(compactToolResult(message));
            } else {
                view.add(message);
            }
        }
        return List.copyOf(view);
    }

    private int totalTokens(List<LlmMessage> messages) {
        return messages.stream()
                .map(LlmMessage::content)
                .mapToInt(tokenEstimator::estimate)
                .sum();
    }

    private LlmMessage compactToolResult(LlmMessage message) {
        return new LlmMessage(
                message.messageId(),
                message.role(),
                OLD_TOOL_RESULT_PLACEHOLDER,
                message.toolCalls(),
                message.toolUseId(),
                message.extras()
        );
    }

    private int thresholdTokens() {
        return properties.getContext().getMicroCompactThresholdTokens();
    }
}
