package com.ai.agent.web.admin.service;

import com.ai.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAccessGuardTest {

    private AgentProperties properties;
    private Environment environment;
    private AdminAccessGuard guard;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        environment = mock(Environment.class);
        guard = new AdminAccessGuard(properties, environment);
    }

    @Test
    @DisplayName("local profile + blank token should be allowed")
    void localProfile_blankToken_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        assertThat(guard.checkAccess(null)).isTrue();
        assertThat(guard.checkAccess("")).isTrue();
    }

    @Test
    @DisplayName("demo profile + blank token should be allowed")
    void demoProfile_blankToken_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"demo"});

        assertThat(guard.checkAccess(null)).isTrue();
        assertThat(guard.checkAccess("")).isTrue();
    }

    @Test
    @DisplayName("local,test profiles + blank token should be allowed (local matched)")
    void localTestProfiles_blankToken_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local", "test"});

        assertThat(guard.checkAccess(null)).isTrue();
        assertThat(guard.checkAccess("")).isTrue();
    }

    @Test
    @DisplayName("production,localish profiles + blank token should be denied (no exact match)")
    void productionLocalishProfiles_blankToken_denied() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"production", "localish"});

        assertThatThrownBy(() -> guard.checkAccess(null))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
        assertThatThrownBy(() -> guard.checkAccess(""))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
    }

    @Test
    @DisplayName("prod profile + blank token should be denied")
    void prodProfile_blankToken_denied() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> guard.checkAccess(null))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
        assertThatThrownBy(() -> guard.checkAccess(""))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
    }

    @Test
    @DisplayName("enabled=false should be denied regardless of profile or token")
    void enabledFalse_denied() {
        properties.getAdmin().setEnabled(false);
        properties.getAdmin().setToken("secret-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        assertThatThrownBy(() -> guard.checkAccess(null))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin console disabled");
        assertThatThrownBy(() -> guard.checkAccess("secret-token"))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin console disabled");
    }

    @Test
    @DisplayName("token match should be allowed")
    void tokenMatch_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThat(guard.checkAccess("secret-token")).isTrue();
    }

    @Test
    @DisplayName("token mismatch should be denied")
    void tokenMismatch_denied() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        assertThatThrownBy(() -> guard.checkAccess("wrong-token"))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("invalid admin token");
        assertThatThrownBy(() -> guard.checkAccess("secret-token-extra"))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("invalid admin token");
    }

    @Test
    @DisplayName("local profile with configured token still allows blank token")
    void localProfile_withConfiguredToken_blankToken_allowed() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("configured-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{"local"});

        // Local profile bypasses token check even when token is configured
        assertThat(guard.checkAccess(null)).isTrue();
        assertThat(guard.checkAccess("")).isTrue();
    }

    @Test
    @DisplayName("empty profiles array should deny blank token")
    void emptyProfiles_blankToken_denied() {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("secret-token");
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        assertThatThrownBy(() -> guard.checkAccess(null))
                .isInstanceOf(AdminAccessGuard.AdminAccessDeniedException.class)
                .hasMessageContaining("admin token required");
    }
}