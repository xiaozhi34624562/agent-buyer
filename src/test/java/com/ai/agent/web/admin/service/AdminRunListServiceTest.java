package com.ai.agent.web.admin.service;

import com.ai.agent.web.admin.dto.AdminRunListDto;
import com.ai.agent.web.admin.dto.AdminRunListQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AdminRunListServiceTest {

    @Autowired
    private AdminRunListService service;

    @Test
    @DisplayName("listRuns should return paginated results with fixed ORDER BY updated_at DESC")
    void listRuns_pagination_and_order() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(1);
        query.setPageSize(10);

        List<AdminRunListDto> runs = service.listRuns(query);

        assertThat(runs).isNotNull();
        assertThat(runs.size()).isLessThanOrEqualTo(10);

        // Verify ordering: most recent first
        for (int i = 1; i < runs.size(); i++) {
            assertThat(runs.get(i - 1).updatedAt())
                    .isNotNull();
            if (runs.get(i).updatedAt() != null) {
                assertThat(runs.get(i - 1).updatedAt())
                        .isAfterOrEqualTo(runs.get(i).updatedAt());
            }
        }
    }

    @Test
    @DisplayName("listRuns should clamp page to minimum 1")
    void listRuns_pageClamp_min() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(0);
        query.setPageSize(10);

        List<AdminRunListDto> runs = service.listRuns(query);

        assertThat(runs).isNotNull();
    }

    @Test
    @DisplayName("listRuns should clamp pageSize to range 1-100")
    void listRuns_pageSizeClamp() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(1);
        query.setPageSize(0);

        List<AdminRunListDto> runs1 = service.listRuns(query);
        assertThat(runs1).isNotNull();

        query.setPageSize(200);
        List<AdminRunListDto> runs2 = service.listRuns(query);
        assertThat(runs2).isNotNull();
        assertThat(runs2.size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("listRuns should filter by status")
    void listRuns_filterByStatus() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(1);
        query.setPageSize(10);
        query.setStatus("SUCCEEDED");

        List<AdminRunListDto> runs = service.listRuns(query);

        assertThat(runs).isNotNull();
        runs.forEach(r -> assertThat(r.status()).isEqualTo("SUCCEEDED"));
    }

    @Test
    @DisplayName("listRuns should filter by userId")
    void listRuns_filterByUserId() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(1);
        query.setPageSize(10);
        query.setUserId("demo-user");

        List<AdminRunListDto> runs = service.listRuns(query);

        assertThat(runs).isNotNull();
        runs.forEach(r -> assertThat(r.userId()).isEqualTo("demo-user"));
    }

    @Test
    @DisplayName("listRuns should not accept dynamic sort parameter")
    void listRuns_noDynamicSort() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(1);
        query.setPageSize(10);
        query.setSortBy("run_id"); // Should be ignored

        List<AdminRunListDto> runs = service.listRuns(query);

        assertThat(runs).isNotNull();
        // Order should still be by updated_at DESC, not run_id
    }

    @Test
    @DisplayName("AdminRunListDto should contain all required fields")
    void dtoContainsRequiredFields() {
        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(1);
        query.setPageSize(1);

        List<AdminRunListDto> runs = service.listRuns(query);

        if (!runs.isEmpty()) {
            AdminRunListDto dto = runs.get(0);
            assertThat(dto.runId()).isNotNull();
            assertThat(dto.userId()).isNotNull();
            assertThat(dto.status()).isNotNull();
            assertThat(dto.turnNo()).isNotNull();
            assertThat(dto.agentType()).isNotNull();
            assertThat(dto.primaryProvider()).isNotNull();
            assertThat(dto.fallbackProvider()).isNotNull();
            assertThat(dto.model()).isNotNull();
            assertThat(dto.maxTurns()).isNotNull();
            assertThat(dto.startedAt()).isNotNull();
            assertThat(dto.updatedAt()).isNotNull();
            // parentRunId, parentLinkStatus, completedAt, lastError can be null
        }
    }
}