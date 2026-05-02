package com.ai.agent.llm.toolcall;

import com.ai.agent.llm.model.ToolCallMessage;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用组装器。
 * 用于将流式输出中的增量工具调用片段组装成完整的工具调用消息。
 */
public final class ToolCallAssembler {
    private final Map<Integer, PartialToolCall> partials = new HashMap<>();

    /**
     * 追加增量内容到指定索引的工具调用。
     *
     * @param index         工具调用索引
     * @param id            工具调用ID增量
     * @param nameDelta     工具名称增量
     * @param argumentsDelta 参数JSON增量
     */
    public void append(int index, String id, String nameDelta, String argumentsDelta) {
        PartialToolCall partial = partials.computeIfAbsent(index, ignored -> new PartialToolCall());
        if (id != null && !id.isBlank()) {
            partial.id = id;
        }
        if (nameDelta != null && !nameDelta.isBlank()) {
            partial.name.append(nameDelta);
        }
        if (argumentsDelta != null && !argumentsDelta.isEmpty()) {
            partial.arguments.append(argumentsDelta);
        }
    }

    /**
     * 完成组装，返回所有完整的工具调用消息。
     *
     * @return 按索引排序的工具调用消息列表
     */
    public List<ToolCallMessage> complete() {
        return partials.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(entry -> {
                    PartialToolCall partial = entry.getValue();
                    return new ToolCallMessage(
                            partial.id == null || partial.id.isBlank() ? "call_" + entry.getKey() : partial.id,
                            partial.name.toString(),
                            partial.arguments.isEmpty() ? "{}" : partial.arguments.toString()
                    );
                })
                .toList();
    }

    private static final class PartialToolCall {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
