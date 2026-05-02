package com.ai.agent.loop;

import com.ai.agent.application.RunAccessManager;
import com.ai.agent.web.dto.AgentRunRequest;
import com.ai.agent.web.dto.AgentRunResult;
import com.ai.agent.web.dto.UserMessage;
import com.ai.agent.web.sse.AgentEventSink;

/**
 * Agent运行循环接口，定义Agent执行的核心操作。
 * <p>
 * 提供新建运行和继续运行两种核心能力，支持通过SSE事件流向客户端推送执行过程。
 * </p>
 */
public interface AgentLoop {

    /**
     * 创建并执行一个新的Agent运行。
     *
     * @param userId  用户标识
     * @param request 运行请求，包含初始消息和配置参数
     * @param sink    SSE事件接收器，用于推送执行过程事件
     * @return 运行结果，包含运行ID、最终状态和输出内容
     */
    AgentRunResult run(String userId, AgentRunRequest request, AgentEventSink sink);

    /**
     * 继续一个已暂停的Agent运行（自动获取继续许可）。
     *
     * @param userId  用户标识
     * @param runId   运行标识
     * @param message 用户输入的后续消息
     * @param sink    SSE事件接收器
     * @return 运行结果
     */
    AgentRunResult continueRun(String userId, String runId, UserMessage message, AgentEventSink sink);

    /**
     * 继续一个已暂停的Agent运行（使用预先获取的继续许可）。
     *
     * @param userId  用户标识
     * @param runId   运行标识
     * @param message 用户输入的后续消息
     * @param sink    SSE事件接收器
     * @param permit  继续运行许可，由RunAccessManager颁发
     * @return 运行结果
     */
    AgentRunResult continueRun(
            String userId,
            String runId,
            UserMessage message,
            AgentEventSink sink,
            RunAccessManager.ContinuationPermit permit
    );
}
