package com.ai.agent.llm.compact;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 大结果溢出处理器。
 * <p>
 * 当工具调用返回的结果内容过长时，将其截断并在中间插入结果路径引用，
 * 以避免上下文过长导致token消耗过大。
 * </p>
 */
@Component
public final class LargeResultSpiller {
    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;

    /**
     * 构造函数。
     *
     * @param properties     Agent配置属性
     * @param tokenEstimator Token估算器
     */
    public LargeResultSpiller(AgentProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 处理消息列表中的大结果工具返回。
     * <p>
     * 对超过阈值token数的工具返回结果进行截断处理，
     * 在截断后的内容中插入结果路径引用，保留头部和尾部部分内容。
     * </p>
     *
     * @param runId   运行ID
     * @param messages 待处理的消息列表
     * @return 处理后的消息列表
     */
    public List<LlmMessage> spill(String runId, List<LlmMessage> messages) {
        List<LlmMessage> view = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            if (message.role() != MessageRole.TOOL || tokenEstimator.estimate(message.content()) <= thresholdTokens()) {
                view.add(message);
                continue;
            }
            view.add(spillToolResult(runId, message));
        }
        return List.copyOf(view);
    }

    /**
     * 对单个工具结果消息进行溢出处理。
     *
     * @param runId   运行ID
     * @param message 工具结果消息
     * @return 处理后的消息，包含截断内容和结果路径引用
     */
    private LlmMessage spillToolResult(String runId, LlmMessage message) {
        String content = tokenEstimator.head(message.content(), headTokens())
                + "\n<resultPath>" + resultPath(runId, message.toolUseId()) + "</resultPath>\n"
                + tokenEstimator.tail(message.content(), tailTokens());
        return new LlmMessage(
                message.messageId(),
                message.role(),
                content,
                message.toolCalls(),
                message.toolUseId(),
                message.extras()
        );
    }

    /**
     * 构建结果路径引用字符串。
     *
     * @param runId     运行ID
     * @param toolUseId 工具调用ID
     * @return 结果路径字符串
     */
    private String resultPath(String runId, String toolUseId) {
        return "trajectory://runs/" + runId + "/tool-results/" + toolUseId + "/full";
    }

    /**
     * 获取触发溢出处理的token阈值。
     *
     * @return token阈值
     */
    private int thresholdTokens() {
        return properties.getContext().getLargeResultThresholdTokens();
    }

    /**
     * 获取溢出处理时保留的头部token数。
     *
     * @return 头部token数
     */
    private int headTokens() {
        return properties.getContext().getLargeResultHeadTokens();
    }

    /**
     * 获取溢出处理时保留的尾部token数。
     *
     * @return 尾部token数
     */
    private int tailTokens() {
        return properties.getContext().getLargeResultTailTokens();
    }
}
