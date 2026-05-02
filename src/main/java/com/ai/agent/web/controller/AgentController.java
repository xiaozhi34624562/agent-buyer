package com.ai.agent.web.controller;

import com.ai.agent.application.AgentRequestPolicy;
import com.ai.agent.application.AgentRunApplicationService;
import com.ai.agent.application.RunAccessManager;
import com.ai.agent.application.RunInterruptService;
import com.ai.agent.security.SensitivePayloadSanitizer;
import com.ai.agent.trajectory.dto.AgentRunTrajectoryDto;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.ContinueRunRequest;
import com.ai.agent.web.sse.AgentEventRecorder;
import com.ai.agent.web.sse.AgentEventSink;
import com.ai.agent.web.sse.ErrorEvent;
import com.ai.agent.web.sse.RecordingAgentEventSink;
import com.ai.agent.web.sse.SseAgentEventSink;
import com.ai.agent.web.sse.SseMetrics;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 运行操作的主 REST 控制器。
 * <p>
 * 提供以下接口：
 * - 创建新运行（SSE 流式响应）
 * - 继续暂停的运行（SSE 流式响应）
 * - 查询运行轨迹和状态
 * - 终止活跃运行
 * - 中断正在执行的 Agent 循环
 * </p>
 * <p>
 * 所有接口需要 X-User-Id 请求头标识用户。
 * 运行访问由 RunAccessManager 控制，确保用户只能访问自己的运行。
 * </p>
 * <p>
 * SSE 接口返回流式响应，实时推送 Agent 进度。
 * Emitter 超时时间 310 秒（5 分钟 + 缓冲）。
 * </p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRunApplicationService applicationService;
    private final ExecutorService agentExecutor;
    private final ScheduledExecutorService sseScheduler;
    private final SseMetrics sseMetrics;
    private final AgentEventRecorder eventRecorder;
    private final SensitivePayloadSanitizer sanitizer;

    /**
     * 构造控制器实例。
     *
     * @param applicationService Agent 运行应用服务
     * @param agentExecutor      Agent 任务执行器
     * @param sseScheduler       SSE 心跳调度器
     * @param sseMetrics         SSE 指标收集器
     * @param eventRecorder      事件记录器（调试用）
     * @param sanitizer          敏感数据脱敏器
     */
    public AgentController(
            AgentRunApplicationService applicationService,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("sseScheduler") ScheduledExecutorService sseScheduler,
            SseMetrics sseMetrics,
            AgentEventRecorder eventRecorder,
            SensitivePayloadSanitizer sanitizer
    ) {
        this.applicationService = applicationService;
        this.agentExecutor = agentExecutor;
        this.sseScheduler = sseScheduler;
        this.sseMetrics = sseMetrics;
        this.eventRecorder = eventRecorder;
        this.sanitizer = sanitizer;
    }

    /**
     * 创建新 Agent 运行，返回 SSE 流。
     * <p>
     * 运行提交到 agentExecutor 执行，事件通过 SSE 流式推送给客户端。
     * 流事件类型：
     * - text_delta: 助手消息片段
     * - tool_use: 工具调用发起
     * - tool_progress: 中间进度更新
     * - tool_result: 工具执行结果
     * - final: 运行完成状态
     * - error: 失败事件
     * </p>
     *
     * @param userId  用户标识（X-User-Id 请求头）
     * @param request 运行请求，包含消息和配置
     * @return SSE emitter 用于流式推送 Agent 事件
     */
    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createRun(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody AgentRunRequest request
    ) {
        return submitSse(applicationService.createRun(requiredUserId(userId), request));
    }

    /**
     * 继续暂停或等待确认的运行，接收用户输入。
     * <p>
     * 适用场景：
     * - Agent 请求更多信息时（PAUSED 状态）
     * - 确认或拒绝危险操作时（WAITING_USER_CONFIRMATION 状态）
     * </p>
     * <p>
     * 返回 SSE 流，事件类型与 createRun 相同。
     * </p>
     *
     * @param userId  用户标识
     * @param runId   要继续的运行标识
     * @param request 继续请求，包含用户消息
     * @return SSE emitter 用于流式推送 Agent 事件
     */
    @PostMapping(value = "/runs/{runId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId,
            @Valid @RequestBody ContinueRunRequest request
    ) {
        return submitSse(applicationService.continueRun(requiredUserId(userId), runId, request));
    }

    /**
     * 获取运行的完整轨迹。
     * <p>
     * 返回指定运行的所有消息、LLM 调用、工具调用和事件。
     * 用于查看运行历史和调试已完成的运行。
     * </p>
     *
     * @param userId 用户标识（必须与运行所有者匹配）
     * @param runId  运行标识
     * @return 包含完整运行数据的轨迹 DTO
     */
    @GetMapping("/runs/{runId}")
    public AgentRunTrajectoryDto getRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId
    ) {
        return applicationService.queryRun(requiredUserId(userId), runId);
    }

    /**
     * 立即终止活跃运行。
     * <p>
     * 结束运行并取消所有待执行的工具调用。
     * 运行状态变为 CANCELLED。
     * 仅对 CREATED、RUNNING、WAITING 状态的运行有效。
     * </p>
     *
     * @param userId 用户标识（必须与运行所有者匹配）
     * @param runId  要终止的运行标识
     * @return 终止响应，包含最终状态
     */
    @PostMapping("/runs/{runId}/abort")
    public AgentRunApplicationService.AbortRunResponse abortRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId
    ) {
        return applicationService.abortRun(requiredUserId(userId), runId);
    }

    /**
     * 中断正在执行的 Agent 循环。
     * <p>
     * 发送中断信号，让 Agent 在当前 LLM 调用完成后停止，
     * 进入 PAUSED 状态等待用户进一步指示。
     * 与 abort 不同，interrupt 是优雅停止，不取消已发起的工具调用。
     * </p>
     *
     * @param userId 用户标识（必须与运行所有者匹配）
     * @param runId  要中断的运行标识
     * @return 中断响应
     */
    @PostMapping("/runs/{runId}/interrupt")
    public RunInterruptService.InterruptRunResponse interruptRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId
    ) {
        return applicationService.interruptRun(requiredUserId(userId), runId);
    }

    /**
     * 验证并规范化用户标识。
     *
     * @param userId 原始用户标识
     * @return 规范化后的用户标识
     * @throws AuthenticationRequiredException 用户标识为空或空白
     */
    private String requiredUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AuthenticationRequiredException();
        }
        return userId.trim();
    }

    /**
     * 提交 SSE 流式执行计划。
     * <p>
     * 创建 SSE emitter，设置超时时间 310 秒，
     * 将执行任务提交到 agentExecutor，并处理异常情况。
     * </p>
     *
     * @param plan 运行流执行计划
     * @return SSE emitter
     */
    private SseEmitter submitSse(AgentRunApplicationService.RunStreamPlan plan) {
        SseEmitter emitter = new SseEmitter(310_000L);
        SseAgentEventSink sseSink = new SseAgentEventSink(emitter, sseScheduler, sseMetrics, sanitizer);
        AgentEventSink sink = new RecordingAgentEventSink(sseSink, eventRecorder);
        Map<String, String> parentMdc = MDC.getCopyOfContextMap();
        try {
            agentExecutor.submit(() -> runWithMdc(parentMdc, () -> {
                try {
                    plan.action().run(sink);
                } catch (Exception e) {
                    log.error("agent request failed", e);
                    safeError(sink, e);
                } finally {
                    sseSink.close();
                    emitter.complete();
                }
            }));
        } catch (RejectedExecutionException e) {
            log.error("agent request rejected by executor", e);
            plan.onExecutorRejected().run();
            safeError(sink, e);
            sseSink.close();
            emitter.completeWithError(e);
            throw e;
        }
        return emitter;
    }

    /**
     * 安全发送错误事件。
     * <p>
     * 当客户端已断开连接时，忽略发送失败。
     * </p>
     *
     * @param sink 事件接收器
     * @param e    异常
     */
    private void safeError(AgentEventSink sink, Exception e) {
        try {
            sink.onError(new ErrorEvent(null, e.getMessage()));
        } catch (Exception ignored) {
            // 客户端可能已断开连接
            log.debug("failed to send error event because client is gone", ignored);
        }
    }

    /**
     * 在指定 MDC 上下文中执行任务。
     * <p>
     * 保持线程池执行时的日志上下文一致性，
     * 任务完成后恢复原始上下文。
     * </p>
     *
     * @param context 父线程的 MDC 上下文
     * @param task    要执行的任务
     */
    private void runWithMdc(Map<String, String> context, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (context == null || context.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(context);
            }
            task.run();
        } finally {
            if (previous == null || previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AgentRunApplicationService.RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", "rate limit exceeded"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AgentRunApplicationService.ServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "agent is shutting down"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RunInterruptService.InterruptDisabledException.class)
    public ResponseEntity<Map<String, String>> interruptDisabled() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "run interrupt is disabled"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AgentRequestPolicy.InvalidAgentRequestException.class)
    public ResponseEntity<Map<String, String>> invalidAgentRequest(AgentRequestPolicy.InvalidAgentRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({
            MissingRequestHeaderException.class,
            AuthenticationRequiredException.class
    })
    public ResponseEntity<Map<String, String>> authenticationRequired() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "authenticated user is required"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RunAccessManager.RunAccessDeniedException.class)
    public ResponseEntity<Map<String, String>> accessDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "run access denied"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RunAccessManager.RunNotFoundException.class)
    public ResponseEntity<Map<String, String>> runNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "run not found"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RunAccessManager.RunContinuationNotAllowedException.class)
    public ResponseEntity<Map<String, String>> continuationNotAllowed() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "run is not waiting for continuation"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RunAccessManager.RunContinuationLockedException.class)
    public ResponseEntity<Map<String, String>> continuationLocked() {
        return ResponseEntity.status(HttpStatus.LOCKED)
                .body(Map.of("message", "run continuation is already in progress"));
    }

    private static final class AuthenticationRequiredException extends RuntimeException {
    }
}
