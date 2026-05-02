package com.ai.agent.web.sse;

import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.mapper.AgentEventMapper;
import com.ai.agent.persistence.mapper.AgentToolProgressMapper;
import com.ai.agent.security.SensitivePayloadSanitizer;
import com.ai.agent.util.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public final class AgentEventRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentEventRecorder.class);

    private final AgentEventMapper eventMapper;
    private final AgentToolProgressMapper progressMapper;
    private final SensitivePayloadSanitizer sanitizer;
    private final ExecutorService eventExecutor;
    private final MeterRegistry meterRegistry;

    public AgentEventRecorder(
            AgentEventMapper eventMapper,
            AgentToolProgressMapper progressMapper,
            SensitivePayloadSanitizer sanitizer,
            @Qualifier("eventExecutor") ExecutorService eventExecutor,
            MeterRegistry meterRegistry
    ) {
        this.eventMapper = eventMapper;
        this.progressMapper = progressMapper;
        this.sanitizer = sanitizer;
        this.eventExecutor = eventExecutor;
        this.meterRegistry = meterRegistry;
    }

    public void recordEvent(String runId, String eventType, Object payload) {
        submit(() -> {
            AgentEventEntity entity = new AgentEventEntity();
            entity.setEventId(Ids.newId("evt"));
            entity.setRunId(runId);
            entity.setEventType(eventType);
            entity.setPayloadJson(toJson(payload));
            eventMapper.insert(entity);
        }, "event");
    }

    public void recordProgress(ToolProgressEvent event) {
        submit(() -> {
            AgentToolProgressEntity entity = new AgentToolProgressEntity();
            entity.setProgressId(Ids.newId("prog"));
            entity.setRunId(event.runId());
            entity.setToolCallId(event.toolCallId());
            entity.setStage(event.stage());
            entity.setMessage(sanitizer.sanitizeText(event.message()));
            entity.setPercent(event.percent());
            progressMapper.insert(entity);
        }, "progress");
    }

    private void submit(Runnable task, String type) {
        try {
            eventExecutor.submit(() -> {
                try {
                    task.run();
                    meterRegistry.counter("agent.event_queue.write", "type", type, "status", "ok").increment();
                } catch (Exception e) {
                    log.warn("agent async {} write failed", type, e);
                    meterRegistry.counter("agent.event_queue.write", "type", type, "status", "failed").increment();
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("agent async {} write dropped because queue is full", type, e);
            meterRegistry.counter("agent.event_queue.write", "type", type, "status", "dropped").increment();
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return sanitizer.sanitizeJson(value);
    }
}
