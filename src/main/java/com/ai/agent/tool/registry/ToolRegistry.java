package com.ai.agent.tool.registry;

import com.ai.agent.tool.core.Tool;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public final class ToolRegistry {
    private final Map<String, Tool> toolsByCanonicalName;

    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> next = new LinkedHashMap<>();
        tools.stream()
                .sorted(Comparator.comparing(tool -> tool.schema().name()))
                .forEach(tool -> {
                    String canonical = canonicalize(tool.schema().name());
                    Tool previous = next.putIfAbsent(canonical, tool);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "canonical tool name collision: " + previous.schema().name() + " and " + tool.schema().name()
                        );
                    }
                });
        this.toolsByCanonicalName = Map.copyOf(next);
    }

    public Tool resolve(String rawName) {
        Tool tool = toolsByCanonicalName.get(canonicalize(rawName));
        if (tool == null) {
            throw new IllegalArgumentException("unknown tool: " + rawName);
        }
        return tool;
    }

    public Collection<Tool> all() {
        return toolsByCanonicalName.values();
    }

    public List<Tool> allowed(Set<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return List.copyOf(all());
        }
        Set<String> allowedCanonical = allowedToolNames.stream()
                .map(ToolRegistry::canonicalize)
                .collect(Collectors.toSet());
        return toolsByCanonicalName.entrySet().stream()
                .filter(entry -> allowedCanonical.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    public static String canonicalize(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase()
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
                .trim();
    }
}
