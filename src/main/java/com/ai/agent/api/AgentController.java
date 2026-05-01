package com.ai.agent.api;

import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.tool.redis.RedisToolStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentLoop agentLoop;
    private final RunAdmissionController admissionController;
    private final RedisRateLimiter rateLimiter;
    private final ContinuationLockService continuationLockService;
    private final TrajectoryStore trajectoryStore;
    private final ExecutorService agentExecutor;
    private final ScheduledExecutorService sseScheduler;
    private final RedisToolStore redisToolStore;
    private final MeterRegistry meterRegistry;

    public AgentController(
            AgentLoop agentLoop,
            RunAdmissionController admissionController,
            RedisRateLimiter rateLimiter,
            ContinuationLockService continuationLockService,
            TrajectoryStore trajectoryStore,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            @Qualifier("sseScheduler") ScheduledExecutorService sseScheduler,
            RedisToolStore redisToolStore,
            MeterRegistry meterRegistry
    ) {
        this.agentLoop = agentLoop;
        this.admissionController = admissionController;
        this.rateLimiter = rateLimiter;
        this.continuationLockService = continuationLockService;
        this.trajectoryStore = trajectoryStore;
        this.agentExecutor = agentExecutor;
        this.sseScheduler = sseScheduler;
        this.redisToolStore = redisToolStore;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createRun(
            @RequestHeader(value = "X-User-Id", defaultValue = "demo-user") String userId,
            @Valid @RequestBody AgentRunRequest request
    ) {
        if (!admissionController.isAccepting()) {
            throw new ServiceUnavailableException();
        }
        if (!rateLimiter.allowRun(userId)) {
            throw new RateLimitExceededException();
        }
        return submitSse(sink -> agentLoop.run(userId, request, sink));
    }

    @PostMapping(value = "/runs/{runId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueRun(
            @RequestHeader(value = "X-User-Id", defaultValue = "demo-user") String userId,
            @PathVariable String runId,
            @Valid @RequestBody ContinueRunRequest request
    ) {
        if (!admissionController.isAccepting()) {
            throw new ServiceUnavailableException();
        }
        return submitSse(sink -> agentLoop.continueRun(userId, runId, request.message(), sink));
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        return Map.of(
                "runId", runId,
                "status", trajectoryStore.findRunStatus(runId),
                "messages", trajectoryStore.loadMessages(runId)
        );
    }

    @PostMapping("/runs/{runId}/abort")
    public Map<String, Object> abortRun(
            @RequestHeader(value = "X-User-Id", defaultValue = "demo-user") String userId,
            @PathVariable String runId
    ) {
        if (!userId.equals(trajectoryStore.findRunUserId(runId))) {
            throw new IllegalArgumentException("run does not belong to current user");
        }
        redisToolStore.abort(runId, "user_abort");
        continuationLockService.releaseRun(runId);
        trajectoryStore.updateRunStatus(runId, RunStatus.CANCELLED, "user_abort");
        return Map.of("runId", runId, "status", RunStatus.CANCELLED);
    }

    private SseEmitter submitSse(Consumer<AgentEventSink> action) {
        SseEmitter emitter = new SseEmitter(310_000L);
        SseAgentEventSink sink = new SseAgentEventSink(emitter, sseScheduler, meterRegistry);
        agentExecutor.submit(() -> {
            try {
                action.accept(sink);
            } catch (Exception e) {
                safeError(sink, e);
            } finally {
                sink.close();
                emitter.complete();
            }
        });
        return emitter;
    }

    private void safeError(AgentEventSink sink, Exception e) {
        try {
            sink.onError(new ErrorEvent(null, e.getMessage()));
        } catch (Exception ignored) {
            // The client may already be disconnected.
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", "rate limit exceeded"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "agent is shutting down"));
    }

    private static final class RateLimitExceededException extends RuntimeException {
    }

    private static final class ServiceUnavailableException extends RuntimeException {
    }
}
