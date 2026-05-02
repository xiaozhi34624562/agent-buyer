package com.ai.agent.llm.compact;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.llm.model.ToolCallMessage;
import com.ai.agent.llm.provider.LlmCallObserver;
import com.ai.agent.llm.summary.SummaryGenerationContext;
import com.ai.agent.llm.summary.SummaryGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 概要压缩器。
 * <p>
 * 当上下文总token数超过阈值时，通过生成摘要来压缩历史消息。
 * 将需要压缩的消息块生成摘要，替换为摘要消息，保留系统消息、首条非系统消息和最近消息。
 * </p>
 */
@Component
public final class SummaryCompactor {
    private static final int FIRST_NON_SYSTEM_MESSAGES = 3;
    private static final int RECENT_MESSAGE_FLOOR = 3;

    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;
    private final SummaryGenerator summaryGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param properties        Agent配置属性
     * @param tokenEstimator    Token估算器
     * @param summaryGenerator  摘要生成器
     * @param objectMapper      JSON对象映射器
     */
    public SummaryCompactor(
            AgentProperties properties,
            TokenEstimator tokenEstimator,
            SummaryGenerator summaryGenerator,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
        this.summaryGenerator = summaryGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行概要压缩，使用默认的调用观察者。
     *
     * @param runId   运行ID
     * @param messages 待处理的消息列表
     * @return 压缩后的消息列表
     */
    public List<LlmMessage> compact(String runId, List<LlmMessage> messages) {
        return compact(new SummaryGenerationContext(runId, 0, LlmCallObserver.NOOP), messages);
    }

    /**
     * 执行概要压缩。
     * <p>
     * 如果总token数未超过阈值，直接返回原消息列表。
     * 否则，将消息分块，保留重要消息块，对其他消息块生成摘要并替换。
     * </p>
     *
     * @param context  摘要生成上下文
     * @param messages 待处理的消息列表
     * @return 压缩后的消息列表
     */
    public List<LlmMessage> compact(SummaryGenerationContext context, List<LlmMessage> messages) {
        if (totalTokens(messages) < thresholdTokens()) {
            assertWithinHardTokenCap(messages);
            return List.copyOf(messages);
        }
        List<Block> blocks = blocks(messages);
        Set<Integer> preserved = preservedBlockIndexes(blocks);
        List<LlmMessage> messagesToCompact = compactedMessages(blocks, preserved);
        if (messagesToCompact.isEmpty()) {
            assertWithinHardTokenCap(messages);
            return List.copyOf(messages);
        }

        List<String> compactedMessageIds = messagesToCompact.stream()
                .map(LlmMessage::messageId)
                .toList();
        LlmMessage summary = summaryMessage(
                compactedMessageIds,
                validateSummaryContent(summaryGenerator.generate(context, messagesToCompact), compactedMessageIds)
        );
        List<LlmMessage> view = new ArrayList<>();
        boolean summaryInserted = false;
        for (int i = 0; i < blocks.size(); i++) {
            if (preserved.contains(i)) {
                view.addAll(blocks.get(i).messages());
            } else if (!summaryInserted) {
                view.add(summary);
                summaryInserted = true;
            }
        }
        assertWithinHardTokenCap(view);
        return List.copyOf(view);
    }

    /**
     * 将消息列表划分为消息块。
     * <p>
     * 以assistant消息及其对应的tool返回作为一个完整块，
     * 其他消息单独成块。
     * </p>
     *
     * @param messages 消息列表
     * @return 消息块列表
     */
    private List<Block> blocks(List<LlmMessage> messages) {
        Map<String, Integer> toolResultIndexes = toolResultIndexes(messages);
        List<Block> blocks = new ArrayList<>();
        boolean[] consumed = new boolean[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            if (consumed[i]) {
                continue;
            }
            LlmMessage message = messages.get(i);
            if (message.role() != MessageRole.ASSISTANT || message.toolCalls().isEmpty()) {
                blocks.add(new Block(List.of(message)));
                consumed[i] = true;
                continue;
            }
            int blockEnd = i;
            for (ToolCallMessage toolCall : message.toolCalls()) {
                Integer toolResultIndex = toolResultIndexes.get(toolCall.toolUseId());
                if (toolResultIndex != null) {
                    blockEnd = Math.max(blockEnd, toolResultIndex);
                }
            }
            List<LlmMessage> blockMessages = new ArrayList<>();
            for (int j = i; j <= blockEnd; j++) {
                if (!consumed[j]) {
                    blockMessages.add(messages.get(j));
                    consumed[j] = true;
                }
            }
            blocks.add(new Block(List.copyOf(blockMessages)));
        }
        return List.copyOf(blocks);
    }

    /**
     * 构建工具结果的ID到索引的映射。
     *
     * @param messages 消息列表
     * @return 工具结果ID到消息索引的映射
     */
    private Map<String, Integer> toolResultIndexes(List<LlmMessage> messages) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            LlmMessage message = messages.get(i);
            if (message.role() == MessageRole.TOOL) {
                indexes.put(message.toolUseId(), i);
            }
        }
        return indexes;
    }

    /**
     * 确定需要保留的消息块索引集合。
     * <p>
     * 包括系统消息块、首条非系统消息块和最近消息块。
     * </p>
     *
     * @param blocks 消息块列表
     * @return 需保留的消息块索引集合
     */
    private Set<Integer> preservedBlockIndexes(List<Block> blocks) {
        Set<Integer> preserved = new LinkedHashSet<>();
        preserveSystemBlocks(blocks, preserved);
        preserveFirstNonSystemMessages(blocks, preserved);
        preserveRecentMessages(blocks, preserved);
        return preserved;
    }

    /**
     * 保留包含系统消息的消息块。
     *
     * @param blocks   消息块列表
     * @param preserved 保留索引集合
     */
    private void preserveSystemBlocks(List<Block> blocks, Set<Integer> preserved) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).messages().stream().anyMatch(message -> message.role() == MessageRole.SYSTEM)) {
                preserved.add(i);
            }
        }
    }

    /**
     * 保留前几条非系统消息所在的块。
     *
     * @param blocks   消息块列表
     * @param preserved 保留索引集合
     */
    private void preserveFirstNonSystemMessages(List<Block> blocks, Set<Integer> preserved) {
        int seen = 0;
        for (int i = 0; i < blocks.size() && seen < FIRST_NON_SYSTEM_MESSAGES; i++) {
            Block block = blocks.get(i);
            boolean preserveBlock = false;
            for (LlmMessage message : block.messages()) {
                if (message.role() != MessageRole.SYSTEM && seen < FIRST_NON_SYSTEM_MESSAGES) {
                    seen++;
                    preserveBlock = true;
                }
            }
            if (preserveBlock) {
                preserved.add(i);
            }
        }
    }

    /**
     * 保留最近的消息块。
     * <p>
     * 保留最近消息窗口内的块，以及在其之前不超过预算token数的块。
     * </p>
     *
     * @param blocks   消息块列表
     * @param preserved 保留索引集合
     */
    private void preserveRecentMessages(List<Block> blocks, Set<Integer> preserved) {
        int recentMessages = 0;
        int tokenTotal = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Block block = blocks.get(i);
            if (recentMessages < RECENT_MESSAGE_FLOOR) {
                preserved.add(i);
                recentMessages += block.messages().size();
                tokenTotal += block.tokens(tokenEstimator);
                continue;
            }
            int nextTokens = block.tokens(tokenEstimator);
            if (tokenTotal + nextTokens > recentBudgetTokens()) {
                break;
            }
            preserved.add(i);
            tokenTotal += nextTokens;
        }
    }

    /**
     * 获取需要压缩的消息列表。
     * <p>
     * 排除需要保留的消息块中的消息。
     * </p>
     *
     * @param blocks   消息块列表
     * @param preserved 保留索引集合
     * @return 需要压缩的消息列表
     */
    private List<LlmMessage> compactedMessages(List<Block> blocks, Set<Integer> preserved) {
        List<LlmMessage> compacted = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (!preserved.contains(i)) {
                compacted.addAll(blocks.get(i).messages());
            }
        }
        return List.copyOf(compacted);
    }

    /**
     * 创建摘要消息。
     *
     * @param compactedMessageIds 被压缩的消息ID列表
     * @param content             摘要内容
     * @return 摘要消息
     */
    private LlmMessage summaryMessage(List<String> compactedMessageIds, String content) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("compactSummary", true);
        extras.put("compactedMessageIds", compactedMessageIds);
        return new LlmMessage(
                "summary-" + compactedMessageIds.getFirst(),
                MessageRole.ASSISTANT,
                content,
                List.of(),
                null,
                extras
        );
    }

    /**
     * 验证摘要内容的JSON结构。
     * <p>
     * 检查摘要是否包含必需的字段，并验证被压缩的消息ID是否匹配。
     * </p>
     *
     * @param content                      摘要JSON内容
     * @param expectedCompactedMessageIds  期望的被压缩消息ID列表
     * @return 验证通过的摘要内容
     */
    private String validateSummaryContent(String content, List<String> expectedCompactedMessageIds) {
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("compact summary is not valid JSON", e);
        }
        requireObject(root);
        requireText(root, "summaryText");
        requireArray(root, "businessFacts");
        requireArray(root, "toolFacts");
        requireArray(root, "openQuestions");
        List<String> actualIds = requireTextArray(root, "compactedMessageIds");
        if (!actualIds.equals(expectedCompactedMessageIds)) {
            throw new IllegalStateException("compact summary message ids do not match compacted view");
        }
        return content;
    }

    /**
     * 验证JSON节点是否为对象类型。
     *
     * @param root JSON根节点
     */
    private void requireObject(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("compact summary must be a JSON object");
        }
    }

    /**
     * 验证JSON对象中是否存在指定文本字段。
     *
     * @param root     JSON根节点
     * @param fieldName 字段名称
     */
    private void requireText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isTextual()) {
            throw new IllegalStateException("compact summary field missing or invalid: " + fieldName);
        }
    }

    /**
     * 验证JSON对象中是否存在指定数组字段。
     *
     * @param root     JSON根节点
     * @param fieldName 字段名称
     */
    private void requireArray(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalStateException("compact summary field missing or invalid: " + fieldName);
        }
    }

    /**
     * 验证JSON对象中指定数组字段是否为文本数组，并返回数组内容。
     *
     * @param root     JSON根节点
     * @param fieldName 字段名称
     * @return 文本数组内容
     */
    private List<String> requireTextArray(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalStateException("compact summary field missing or invalid: " + fieldName);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : field) {
            if (!item.isTextual()) {
                throw new IllegalStateException("compact summary field contains non-text item: " + fieldName);
            }
            values.add(item.asText());
        }
        return List.copyOf(values);
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
     * 验证消息列表是否在硬性token上限内。
     *
     * @param messages 消息列表
     * @throws IllegalStateException 如果总token数超过硬性上限
     */
    private void assertWithinHardTokenCap(List<LlmMessage> messages) {
        int total = totalTokens(messages);
        int hardCap = properties.getAgentLoop().getHardTokenCap();
        if (total > hardCap) {
            throw new IllegalStateException("compacted context exceeds hard token cap: total="
                    + total + ", cap=" + hardCap);
        }
    }

    /**
     * 获取触发概要压缩的token阈值。
     *
     * @return token阈值
     */
    private int thresholdTokens() {
        return properties.getContext().getSummaryCompactThresholdTokens();
    }

    /**
     * 获取最近消息的token预算。
     *
     * @return token预算
     */
    private int recentBudgetTokens() {
        return properties.getContext().getRecentMessageBudgetTokens();
    }

    /**
     * 消息块记录类。
     * <p>
     * 将一组相关消息作为一个块进行管理，便于压缩策略处理。
     * </p>
     */
    private record Block(List<LlmMessage> messages) {
        /**
         * 计算消息块的token总数。
         *
         * @param tokenEstimator Token估算器
         * @return token总数
         */
        private int tokens(TokenEstimator tokenEstimator) {
            return messages.stream()
                    .map(LlmMessage::content)
                    .mapToInt(tokenEstimator::estimate)
                    .sum();
        }
    }
}
