package com.ai.agent.trajectory.model;

import java.util.List;

public record ContextCompactionDraft(
        String strategy,
        Integer beforeTokens,
        Integer afterTokens,
        List<String> compactedMessageIds
) {
    public ContextCompactionDraft {
        compactedMessageIds = compactedMessageIds == null ? List.of() : List.copyOf(compactedMessageIds);
    }
}
