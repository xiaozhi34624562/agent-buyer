package com.ai.agent.web.controller;

import com.ai.agent.application.AgentRequestPolicy;
import com.ai.agent.application.AgentRunApplicationService;
import com.ai.agent.application.RunAccessManager;
import com.ai.agent.application.RunInterruptService;
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

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRunApplicationService applicationService;
    private final ExecutorService agentExecutor;
    private final ScheduledExecutorService sseScheduler;
    private final SseMetrics sseMetrics;
    private final AgentEventRecorder eventRecorder;

    public AgentController(
            AgentRunApplicationService applicationService,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("sseScheduler") ScheduledExecutorService sseScheduler,
            SseMetrics sseMetrics,
            AgentEventRecorder eventRecorder
    ) {
        this.applicationService = applicationService;
        this.agentExecutor = agentExecutor;
        this.sseScheduler = sseScheduler;
        this.sseMetrics = sseMetrics;
        this.eventRecorder = eventRecorder;
    }

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createRun(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody AgentRunRequest request
    ) {
        return submitSse(applicationService.createRun(requiredUserId(userId), request));
    }

    @PostMapping(value = "/runs/{runId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId,
            @Valid @RequestBody ContinueRunRequest request
    ) {
        return submitSse(applicationService.continueRun(requiredUserId(userId), runId, request));
    }

    @GetMapping("/runs/{runId}")
    public AgentRunTrajectoryDto getRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId
    ) {
        return applicationService.queryRun(requiredUserId(userId), runId);
    }

    @PostMapping("/runs/{runId}/abort")
    public AgentRunApplicationService.AbortRunResponse abortRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId
    ) {
        return applicationService.abortRun(requiredUserId(userId), runId);
    }

    @PostMapping("/runs/{runId}/interrupt")
    public RunInterruptService.InterruptRunResponse interruptRun(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String runId
    ) {
        return applicationService.interruptRun(requiredUserId(userId), runId);
    }

    private String requiredUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AuthenticationRequiredException();
        }
        return userId.trim();
    }

    private SseEmitter submitSse(AgentRunApplicationService.RunStreamPlan plan) {
        SseEmitter emitter = new SseEmitter(310_000L);
        SseAgentEventSink sseSink = new SseAgentEventSink(emitter, sseScheduler, sseMetrics);
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

    private void safeError(AgentEventSink sink, Exception e) {
        try {
            sink.onError(new ErrorEvent(null, e.getMessage()));
        } catch (Exception ignored) {
            // The client may already be disconnected.
            log.debug("failed to send error event because client is gone", ignored);
        }
    }

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
