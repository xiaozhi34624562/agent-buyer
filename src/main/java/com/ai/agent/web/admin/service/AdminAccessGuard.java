package com.ai.agent.web.admin.service;

import com.ai.agent.config.AgentProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AdminAccessGuard {

    private static final Set<String> TOKENLESS_ALLOWED_PROFILES = Set.of("local", "demo");

    private final AgentProperties properties;
    private final Environment environment;

    public AdminAccessGuard(AgentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    public boolean checkAccess(String providedToken) {
        if (!properties.getAdmin().isEnabled()) {
            throw new AdminAccessDeniedException("admin console disabled");
        }

        String[] activeProfiles = environment.getActiveProfiles();
        boolean isTokenlessAllowed = Arrays.stream(activeProfiles)
                .anyMatch(TOKENLESS_ALLOWED_PROFILES::contains);

        String configuredToken = properties.getAdmin().getToken();

        if (configuredToken == null || configuredToken.isBlank()) {
            if (isTokenlessAllowed) {
                return true;
            }
            throw new AdminAccessDeniedException("admin token required for non-local/demo profile");
        }

        if (providedToken == null || providedToken.isBlank()) {
            if (isTokenlessAllowed) {
                return true;
            }
            throw new AdminAccessDeniedException("admin token required");
        }

        if (!tokenMatches(configuredToken, providedToken)) {
            throw new AdminAccessDeniedException("invalid admin token");
        }

        return true;
    }

    private boolean tokenMatches(String configuredToken, String providedToken) {
        return MessageDigest.isEqual(sha256(configuredToken), sha256(providedToken));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static class AdminAccessDeniedException extends RuntimeException {
        public AdminAccessDeniedException(String message) {
            super(message);
        }
    }
}
