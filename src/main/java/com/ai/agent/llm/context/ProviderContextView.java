package com.ai.agent.llm.context;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.trajectory.model.ContextCompactionDraft;
import java.util.List;

/**
 * 提供者上下文视图。
 * <p>
 * 封装发送给LLM提供商的消息列表和压缩记录，
 * 作为上下文构建的最终产物。
 * </p>
 */
public record ProviderContextView(List<LlmMessage> messages, List<ContextCompactionDraft> compactions) {
    /**
     * 简化构造函数，仅包含消息列表。
     *
     * @param messages 消息列表
     */
    public ProviderContextView(List<LlmMessage> messages) {
        this(messages, List.of());
    }

    /**
     * 紧凑构造函数，确保列表不可变。
     */
    public ProviderContextView {
        messages = List.copyOf(messages);
        compactions = compactions == null ? List.of() : List.copyOf(compactions);
    }
}
