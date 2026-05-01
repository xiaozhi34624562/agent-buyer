package com.ai.agent.trajectory;

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
