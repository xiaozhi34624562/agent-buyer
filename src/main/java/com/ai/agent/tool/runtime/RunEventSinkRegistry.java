package com.ai.agent.tool.runtime;

import com.ai.agent.web.sse.AgentEventSink;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 运行事件推送接口注册表，管理与运行关联的SSE事件推送接口。
 *
 * <p>用于在工具执行过程中推送进度事件到客户端，支持运行开始时绑定、结束时解绑。
 */
@Component
public final class RunEventSinkRegistry {
    private final ConcurrentMap<String, AgentEventSink> sinks = new ConcurrentHashMap<>();

    /**
     * 绑定运行的事件推送接口。
     *
     * @param runId 运行标识符
     * @param sink SSE事件推送接口
     */
    public void bind(String runId, AgentEventSink sink) {
        sinks.put(runId, sink);
    }

    /**
     * 解绑运行的事件推送接口。
     *
     * @param runId 运行标识符
     */
    public void unbind(String runId) {
        sinks.remove(runId);
    }

    /**
     * 查找运行的事件推送接口。
     *
     * @param runId 运行标识符
     * @return 事件推送接口，若不存在则返回空
     */
    public Optional<AgentEventSink> find(String runId) {
        return Optional.ofNullable(sinks.get(runId));
    }
}
