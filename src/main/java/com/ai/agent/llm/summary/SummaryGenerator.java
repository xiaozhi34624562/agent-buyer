package com.ai.agent.llm.summary;

import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.provider.LlmCallObserver;
import java.util.List;

/**
 * 摘要生成器接口。
 * 定义对话摘要生成的标准接口，用于压缩历史消息以控制上下文长度。
 */
public interface SummaryGenerator {
    /**
     * 使用简化参数生成摘要。
     *
     * @param runId           运行ID
     * @param messagesToCompact 待压缩的消息列表
     * @return JSON格式的摘要内容
     */
    default String generate(String runId, List<LlmMessage> messagesToCompact) {
        return generate(new SummaryGenerationContext(runId, 0, LlmCallObserver.NOOP), messagesToCompact);
    }

    /**
     * 使用完整上下文生成摘要。
     *
     * @param context        摘要生成上下文
     * @param messagesToCompact 待压缩的消息列表
     * @return JSON格式的摘要内容
     */
    String generate(SummaryGenerationContext context, List<LlmMessage> messagesToCompact);
}
