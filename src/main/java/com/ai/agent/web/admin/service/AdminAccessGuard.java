package com.ai.agent.web.admin.service;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AdminAccessGuard {

    private static final Set<String> TOKENLESS_ALLOWED_PROFILES = Set.of("local", "demo");

    private final AgentProperties properties;

    public AdminAccessGuard(AgentProperties properties) {
        this.properties = properties;
    }

    public boolean checkAccess(String activeProfile, String providedToken) {
        if (!properties.getAdmin().isEnabled()) {
            throw new AdminAccessDeniedException("admin console disabled");
        }

        String configuredToken = properties.getAdmin().getToken();

        if (configuredToken == null || configuredToken.isBlank()) {
            if (TOKENLESS_ALLOWED_PROFILES.contains(activeProfile)) {
                return true;
            }
            throw new AdminAccessDeniedException("admin token required for non-local profile");
        }

        if (providedToken == null || providedToken.isBlank()) {
            if (TOKENLESS_ALLOWED_PROFILES.contains(activeProfile)) {
                return true;
            }
            throw new AdminAccessDeniedException("admin token required");
        }

        if (!configuredToken.equals(providedToken)) {
            throw new AdminAccessDeniedException("invalid admin token");
        }

        return true;
    }

    public static class AdminAccessDeniedException extends RuntimeException {
        public AdminAccessDeniedException(String message) {
            super(message);
        }
    }
}