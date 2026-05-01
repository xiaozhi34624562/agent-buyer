package com.ai.agent.todo;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

@Component
public final class TodoRedisKeys {
    private final AgentProperties properties;

    public TodoRedisKeys(AgentProperties properties) {
        this.properties = properties;
    }

    public String todos(String runId) {
        return base(runId) + ":todos";
    }

    public String reminder(String runId) {
        return base(runId) + ":todo-reminder";
    }

    private String base(String runId) {
        return properties.getRedisKeyPrefix() + ":{run:" + runId + "}";
    }
}
