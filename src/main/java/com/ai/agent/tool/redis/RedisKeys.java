package com.ai.agent.tool.redis;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

@Component
public final class RedisKeys {
    private final AgentProperties properties;

    public RedisKeys(AgentProperties properties) {
        this.properties = properties;
    }

    public String meta(String runId) {
        return base(runId) + ":meta";
    }

    public String queue(String runId) {
        return base(runId) + ":queue";
    }

    public String tools(String runId) {
        return base(runId) + ":tools";
    }

    public String toolUseIds(String runId) {
        return base(runId) + ":tool-use-ids";
    }

    public String leases(String runId) {
        return base(runId) + ":leases";
    }

    public String continuationLock(String runId) {
        return base(runId) + ":continuation-lock";
    }

    public String activeRuns() {
        return properties.getRedisKeyPrefix() + ":active-runs";
    }

    private String base(String runId) {
        return properties.getRedisKeyPrefix() + ":{run:" + runId + "}";
    }
}
