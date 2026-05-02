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

/**
 * Admin 控制台 REST 控制器，用于监控和调试 Agent 运行。
 * <p>
 * 提供以下接口：
 * - 列出所有运行，支持按状态和用户过滤
 * - 查看运行时状态用于调试
 * </p>
 * <p>
 * 所有接口需要 X-Admin-Token 认证。
 * 访问权限由 AdminAccessGuard 控制，验证 token 与配置的管理员凭证匹配。
 * </p>
 */
@RestController
@RequestMapping("/api/admin/console")
public class AdminConsoleController {

    private final AdminAccessGuard accessGuard;
    private final AdminRunListService runListService;
    private final AdminRuntimeStateService runtimeStateService;

    /**
     * 构造控制器实例。
     *
     * @param accessGuard         管理员访问权限守卫
     * @param runListService      运行列表查询服务
     * @param runtimeStateService 运行时状态查询服务
     */
    public AdminConsoleController(
            AdminAccessGuard accessGuard,
            AdminRunListService runListService,
            AdminRuntimeStateService runtimeStateService
    ) {
        this.accessGuard = accessGuard;
        this.runListService = runListService;
        this.runtimeStateService = runtimeStateService;
    }

    /**
     * 列出所有 Agent 运行，支持过滤。
     * <p>
     * 支持以下过滤条件：
     * - status: 运行状态（RUNNING, SUCCEEDED, FAILED, PAUSED 等）
     * - userId: 创建运行的用户
     * - page/pageSize: 分页参数
     * </p>
     *
     * @param adminToken 管理员认证 token
     * @param page       可选，页码（从 1 开始）
     * @param pageSize   可选，每页数量
     * @param status     可选，状态过滤
     * @param userId     可选，用户过滤
     * @return 匹配查询条件的运行摘要列表
     */
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

    /**
     * 获取指定运行的运行时状态。
     * <p>
     * 返回 Redis 状态，包含工具调用、队列、租约和元数据。
     * 用于调试卡住或失败的运行。
     * </p>
     *
     * @param adminToken 管理员认证 token
     * @param runId      运行标识
     * @return 运行时状态 DTO
     */
    @GetMapping("/runs/{runId}/runtime-state")
    public ResponseEntity<AdminRuntimeStateDto> getRuntimeState(
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
            @PathVariable String runId
    ) {
        accessGuard.checkAccess(adminToken);

        AdminRuntimeStateDto state = runtimeStateService.getRuntimeState(runId);
        return ResponseEntity.ok(state);
    }

    /**
     * 处理管理员访问被拒异常。
     * <p>
     * 返回 FORBIDDEN (403) 表示凭证无效，
     * 或 SERVICE_UNAVAILABLE (503) 表示管理控制台已禁用。
     * </p>
     *
     * @param e 访问被拒异常
     * @return 对应状态的错误响应
     */
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