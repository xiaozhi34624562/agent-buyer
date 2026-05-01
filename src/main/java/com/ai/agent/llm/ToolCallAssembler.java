package com.ai.agent.llm;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ToolCallAssembler {
    private final Map<Integer, PartialToolCall> partials = new HashMap<>();

    public void append(int index, String id, String nameDelta, String argumentsDelta) {
        PartialToolCall partial = partials.computeIfAbsent(index, ignored -> new PartialToolCall());
        if (id != null && !id.isBlank()) {
            partial.id = id;
        }
        if (nameDelta != null && !nameDelta.isBlank()) {
            partial.name.append(nameDelta);
        }
        if (argumentsDelta != null && !argumentsDelta.isEmpty()) {
            partial.arguments.append(argumentsDelta);
        }
    }

    public List<ToolCallMessage> complete() {
        return partials.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(entry -> {
                    PartialToolCall partial = entry.getValue();
                    return new ToolCallMessage(
                            partial.id == null || partial.id.isBlank() ? "call_" + entry.getKey() : partial.id,
                            partial.name.toString(),
                            partial.arguments.isEmpty() ? "{}" : partial.arguments.toString()
                    );
                })
                .toList();
    }

    private static final class PartialToolCall {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
