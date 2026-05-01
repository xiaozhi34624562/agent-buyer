package com.ai.agent.skill.command;

import java.util.Map;

public class SkillCommandException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    public SkillCommandException(String code, String message) {
        this(code, message, Map.of());
    }

    public SkillCommandException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
