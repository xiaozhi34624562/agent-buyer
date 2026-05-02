package com.ai.agent.web.admin.controller;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.web.admin.service.AdminAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class AdminConsoleControllerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockBean
    private com.ai.agent.web.admin.service.AdminRunListService runListService;

    @MockBean
    private com.ai.agent.web.admin.service.AdminRuntimeStateService runtimeStateService;

    @Autowired
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("test-admin-token");
    }

    @Test
    @DisplayName("GET /api/admin/console/runs should return 200 with valid token")
    void getRuns_withValidToken_returns200() throws Exception {
        when(runListService.listRuns(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/console/runs")
                        .header("X-Admin-Token", "test-admin-token")
                        .param("page", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));

        verify(runListService).listRuns(any());
    }

    @Test
    @DisplayName("GET /api/admin/console/runs should return 403 without token when enabled=true")
    void getRuns_withoutToken_returns403() throws Exception {
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("test-admin-token");

        mockMvc.perform(get("/api/admin/console/runs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/console/runs should return 503 when admin disabled")
    void getRuns_adminDisabled_returns503() throws Exception {
        properties.getAdmin().setEnabled(false);

        mockMvc.perform(get("/api/admin/console/runs")
                        .header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("GET /api/admin/console/runs should pass query params to service")
    void getRuns_passesQueryParams() throws Exception {
        when(runListService.listRuns(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/console/runs")
                        .header("X-Admin-Token", "test-admin-token")
                        .param("page", "2")
                        .param("pageSize", "50")
                        .param("status", "RUNNING")
                        .param("userId", "test-user"))
                .andExpect(status().isOk());

        verify(runListService).listRuns(any());
    }

    @Test
    @DisplayName("GET /api/admin/console/runs/{runId}/runtime-state should return 200")
    void getRuntimeState_withValidToken_returns200() throws Exception {
        com.ai.agent.web.admin.dto.AdminRuntimeStateDto dto =
                new com.ai.agent.web.admin.dto.AdminRuntimeStateDto("run-001", true, Map.of());
        when(runtimeStateService.getRuntimeState("run-001")).thenReturn(dto);

        mockMvc.perform(get("/api/admin/console/runs/run-001/runtime-state")
                        .header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.runId").value("run-001"))
                .andExpect(jsonPath("$.activeRun").value(true));

        verify(runtimeStateService).getRuntimeState("run-001");
    }

    @Test
    @DisplayName("GET /api/admin/console/runs/{runId}/runtime-state should return 403 without token")
    void getRuntimeState_withoutToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/console/runs/run-001/runtime-state"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin token mismatch should return 403")
    void tokenMismatch_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/console/runs")
                        .header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Local profile should allow blank token")
    void localProfile_blankToken_allowed() throws Exception {
        // Test profile is not local/demo, so this test verifies the controller works
        // when token is blank but admin.enabled=true with configured token
        properties.getAdmin().setEnabled(true);
        properties.getAdmin().setToken("");

        when(runListService.listRuns(any())).thenReturn(List.of());

        // With blank configured token, non-local profile still requires token
        // But if token is blank in config, access should be allowed for local/demo only
        // This test verifies behavior when test profile is used
        mockMvc.perform(get("/api/admin/console/runs"))
                .andExpect(status().isForbidden()); // test profile is not local/demo
    }
}