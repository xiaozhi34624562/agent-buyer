package com.ai.agent.web.admin.service;

import com.ai.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAccessGuardTest {

    private AgentProperties properties;
    private AdminAccessGuard guard;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        guard = new AdminAccessGuard(properties);
    }

    @Test
    @DisplayName("local profile + blank token should be allowed")
    void localProfile_blankToken_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("");

        assertThat(guard.checkAccess("local", null)).isTrue();
        assertThat(guard.checkAccess("local", "")).isTrue();
    }

    @Test
    @DisplayName("demo profile + blank token should be allowed")
    void demoProfile_blankToken_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("");

        assertThat(guard.checkAccess("demo", null)).isTrue();
        assertThat(guard.checkAccess("demo", "")).isTrue();
    }

    @Test
    @DisplayName("non-local profile + blank token should be denied")
    void nonLocalProfile_blankToken_denied() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");

        assertThatThrownBy(() -> guard.checkAccess("prod", null))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
        assertThatThrownBy(() -> guard.checkAccess("prod", ""))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
    }

    @Test
    @DisplayName("enabled=false should be denied regardless of profile or token")
    void enabledFalse_denied() {
        properties.getAdmin().setEnabled(false);
        properties.getAdmin().setToken("secret-token");

        assertThatThrownBy(() -> guard.checkAccess("local", null))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin console disabled");
        assertThatThrownBy(() -> guard.checkAccess("prod", "secret-token"))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin console disabled");
    }

    @Test
    @DisplayName("token match should be allowed")
    void tokenMatch_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");

        assertThat(guard.checkAccess("prod", "secret-token")).isTrue();
        assertThat(guard.checkAccess("staging", "secret-token")).isTrue();
    }

    @Test
    @DisplayName("token mismatch should be denied")
    void tokenMismatch_denied() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");

        assertThatThrownBy(() -> guard.checkAccess("prod", "wrong-token"))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("invalid admin token");
        assertThatThrownBy(() -> guard.checkAccess("prod", "secret-token-extra"))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("invalid admin token");
    }
}