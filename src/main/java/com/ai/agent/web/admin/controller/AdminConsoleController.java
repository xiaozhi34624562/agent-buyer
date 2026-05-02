package com.ai.agent.web.admin.controller;

import com.ai.agent.web.admin.dto.AdminRunListDto;
import com.ai.agent.web.admin.dto.AdminRunListQuery;
import com.ai.agent.web.admin.dto.AdminRuntimeStateDto;
import com.ai.agent.web.admin.service.AdminAccessGuard;
import com.ai.agent.web.admin.service.AdminRunListService;
import com.ai.agent.web.admin.service.AdminRuntimeStateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/console")
public class AdminConsoleController {

    private final AdminAccessGuard accessGuard;
    private final AdminRunListService runListService;
    private final AdminRuntimeStateService runtimeStateService;

    public AdminConsoleController(
            AdminAccessGuard accessGuard,
            AdminRunListService runListService,
            AdminRuntimeStateService runtimeStateService
    ) {
        this.accessGuard = accessGuard;
        this.runListService = runListService;
        this.runtimeStateService = runtimeStateService;
    }

    @GetMapping("/runs")
    public ResponseEntity<List<AdminRunListDto>> listRuns(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "userId", required = false) String userId
    ) {
        accessGuard.checkAccess(adminToken);

        AdminRunListQuery query = new AdminRunListQuery();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setStatus(status);
        query.setUserId(userId);

        List<AdminRunListDto> runs = runListService.listRuns(query);
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/runs/{runId}/runtime-state")
    public ResponseEntity<AdminRuntimeStateDto> getRuntimeState(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @PathVariable String runId
    ) {
        accessGuard.checkAccess(adminToken);

        AdminRuntimeStateDto state = runtimeStateService.getRuntimeState(runId);
        return ResponseEntity.ok(state);
    }

    @ExceptionHandler(AdminAccessGuard.AdminAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AdminAccessGuard.AdminAccessDeniedException e) {
        if (e.getMessage().contains("disabled")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "admin console disabled"));
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", e.getMessage()));
    }
}