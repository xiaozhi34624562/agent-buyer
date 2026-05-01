package com.ai.agent.subagent.registry;

import com.ai.agent.subagent.profile.SubAgentProfile;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class SubAgentRegistry {
    private final Map<String, SubAgentProfile> profilesByType;

    public SubAgentRegistry(List<SubAgentProfile> profiles) {
        Map<String, SubAgentProfile> next = new LinkedHashMap<>();
        profiles.stream()
                .sorted(Comparator.comparing(SubAgentProfile::agentType))
                .forEach(profile -> {
                    SubAgentProfile previous = next.putIfAbsent(profile.agentType(), profile);
                    if (previous != null) {
                        throw new IllegalStateException("duplicate subagent profile: " + profile.agentType());
                    }
                });
        this.profilesByType = Map.copyOf(next);
    }

    public SubAgentProfile resolve(String agentType) {
        SubAgentProfile profile = profilesByType.get(agentType);
        if (profile == null) {
            throw new IllegalArgumentException("unknown subagent profile: " + agentType);
        }
        return profile;
    }
}
