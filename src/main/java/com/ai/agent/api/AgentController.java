package com.ai.agent.api;

import com.ai.agent.trajectory.TrajectoryStore;
import com.ai.agent.domain.RunStatus;
import com.ai.agent.tool.redis.RedisToolStore;
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

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentLoop agentLoop;
    private final RedisRateLimiter rateLimiter;
    private final TrajectoryStore trajectoryStore;
    private final ExecutorService agentExecutor;
    private final RedisToolStore redisToolStore;

    public AgentController(
            AgentLoop agentLoop,
            RedisRateLimiter rateLimiter,
            TrajectoryStore trajectoryStore,
            @Qualifier("agentExecutor") ExecutorService agentExecutor,
            RedisToolStore redisToolStore
    ) {
        this.agentLoop = agentLoop;
        this.rateLimiter = rateLimiter;
        this.trajectoryStore = trajectoryStore;
        this.agentExecutor = agentExecutor;
        this.redisToolStore = redisToolStore;
    }

    @PostMapping(value = "/runs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createRun(
            @RequestHeader(value = "X-User-Id", defaultValue = "demo-user") String userId,
            @Valid @RequestBody AgentRunRequest request
    ) {
        if (!rateLimiter.allowRun(userId)) {
            throw new RateLimitExceededException();
        }
        SseEmitter emitter = new SseEmitter(310_000L);
        agentExecutor.submit(() -> {
            try {
                agentLoop.run(userId, request, new SseAgentEventSink(emitter));
                emitter.complete();
            } catch (Exception e) {
                safeError(emitter, e);
            }
        });
        return emitter;
    }

    @PostMapping(value = "/runs/{runId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter continueRun(
            @RequestHeader(value = "X-User-Id", defaultValue = "demo-user") String userId,
            @PathVariable String runId,
            @Valid @RequestBody ContinueRunRequest request
    ) {
        SseEmitter emitter = new SseEmitter(310_000L);
        agentExecutor.submit(() -> {
            try {
                agentLoop.continueRun(userId, runId, request.message(), new SseAgentEventSink(emitter));
                emitter.complete();
            } catch (Exception e) {
                safeError(emitter, e);
            }
        });
        return emitter;
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
        trajectoryStore.updateRunStatus(runId, RunStatus.CANCELLED, "user_abort");
        return Map.of("runId", runId, "status", RunStatus.CANCELLED);
    }

    private void safeError(SseEmitter emitter, Exception e) {
        try {
            emitter.send(SseEmitter.event().name("error").data(new ErrorEvent(null, e.getMessage())));
        } catch (Exception ignored) {
            // The client may already be disconnected.
        } finally {
            emitter.complete();
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", "rate limit exceeded"));
    }

    private static final class RateLimitExceededException extends RuntimeException {
    }
}
