package com.ai.agent.llm.compact;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.tool.core.Tool;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 微压缩器。
 * <p>
 * 当上下文总token数超过阈值时，将较早的工具返回结果替换为占位符，
 * 只保留最近的消息窗口内的完整内容。这是一种轻量级的上下文压缩策略。
 * </p>
 */
@Component
public final class MicroCompactor {
    /**
     * 旧工具结果占位符，用于替换被删除的过长工具返回内容。
     */
    public static final String OLD_TOOL_RESULT_PLACEHOLDER =
            "<oldToolResult>Tool result is deleted due to long context</oldToolResult>";

    private static final int RECENT_MESSAGE_WINDOW = 3;

    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;

    /**
     * 构造函数。
     *
     * @param properties     Agent配置属性
     * @param tokenEstimator Token估算器
     */
    public MicroCompactor(AgentProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 执行微压缩处理。
     * <p>
     * 如果总token数超过阈值，将较早的工具返回结果替换为占位符，
     * 只保留最近消息窗口内的工具结果完整内容。
     * </p>
     *
     * @param messages 待处理的消息列表
     * @return 压缩后的消息列表
     */
    public List<LlmMessage> compact(List<LlmMessage> messages) {
        if (totalTokens(messages) < thresholdTokens()) {
            return List.copyOf(messages);
        }
        int protectedFromIndex = Math.max(0, messages.size() - RECENT_MESSAGE_WINDOW);
        List<LlmMessage> view = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage message = messages.get(i);
            if (i < protectedFromIndex && message.role() == MessageRole.TOOL) {
                view.add(compactToolResult(message));
            } else {
                view.add(message);
            }
        }
        return List.copyOf(view);
    }

    /**
     * 计算消息列表的总token数。
     *
     * @param messages 消息列表
     * @return 总token数
     */
    private int totalTokens(List<LlmMessage> messages) {
        return messages.stream()
                .map(LlmMessage::content)
                .mapToInt(tokenEstimator::estimate)
                .sum();
    }

    /**
     * 将工具结果消息替换为占位符版本。
     *
     * @param message 原始工具结果消息
     * @return 替换后的消息，内容为占位符
     */
    private LlmMessage compactToolResult(LlmMessage message) {
        return new LlmMessage(
                message.messageId(),
                message.role(),
                OLD_TOOL_RESULT_PLACEHOLDER,
                message.toolCalls(),
                message.toolUseId(),
                message.extras()
        );
    }

    /**
     * 获取触发微压缩的token阈值。
     *
     * @return token阈值
     */
    private int thresholdTokens() {
        return properties.getContext().getMicroCompactThresholdTokens();
    }
}
