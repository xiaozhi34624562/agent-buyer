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

/**
 * Agent 事件记录器。
 *
 * <p>异步持久化 Agent 运行事件到数据库，支持事件数据和工具进度数据的分别存储，
 * 并对敏感数据进行脱敏处理。
 *
 * @author AI Agent
 */
@Component
public final class AgentEventRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentEventRecorder.class);

    private final AgentEventMapper eventMapper;
    private final AgentToolProgressMapper progressMapper;
    private final SensitivePayloadSanitizer sanitizer;
    private final ExecutorService eventExecutor;
    private final MeterRegistry meterRegistry;

    /**
     * 构造事件记录器。
     *
     * @param eventMapper      事件数据 Mapper
     * @param progressMapper   工具进度数据 Mapper
     * @param sanitizer        敏感数据脱敏处理器
     * @param eventExecutor    事件写入异步执行器
     * @param meterRegistry    Micrometer 指标注册器
     */
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

    /**
     * 异步记录事件数据。
     *
     * @param runId      运行实例 ID
     * @param eventType  事件类型
     * @param payload    事件数据对象
     */
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

    /**
     * 异步记录工具进度数据。
     *
     * @param event 工具进度事件
     */
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

    /**
     * 提交异步写入任务。
     *
     * @param task 写入任务
     * @param type 任务类型标识
     */
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

    /**
     * 将对象转换为脱敏后的 JSON 字符串。
     *
     * @param value 待转换对象
     * @return 脱敏后的 JSON 字符串
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return sanitizer.sanitizeJson(value);
    }
}
