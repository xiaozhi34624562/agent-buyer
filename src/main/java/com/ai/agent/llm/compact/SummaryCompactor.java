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

@Component
public final class SummaryCompactor {
    private static final int FIRST_NON_SYSTEM_MESSAGES = 3;
    private static final int RECENT_MESSAGE_FLOOR = 3;

    private final AgentProperties properties;
    private final TokenEstimator tokenEstimator;
    private final SummaryGenerator summaryGenerator;
    private final ObjectMapper objectMapper;

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

    public List<LlmMessage> compact(String runId, List<LlmMessage> messages) {
        return compact(new SummaryGenerationContext(runId, 0, LlmCallObserver.NOOP), messages);
    }

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

    private Set<Integer> preservedBlockIndexes(List<Block> blocks) {
        Set<Integer> preserved = new LinkedHashSet<>();
        preserveSystemBlocks(blocks, preserved);
        preserveFirstNonSystemMessages(blocks, preserved);
        preserveRecentMessages(blocks, preserved);
        return preserved;
    }

    private void preserveSystemBlocks(List<Block> blocks, Set<Integer> preserved) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).messages().stream().anyMatch(message -> message.role() == MessageRole.SYSTEM)) {
                preserved.add(i);
            }
        }
    }

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

    private List<LlmMessage> compactedMessages(List<Block> blocks, Set<Integer> preserved) {
        List<LlmMessage> compacted = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (!preserved.contains(i)) {
                compacted.addAll(blocks.get(i).messages());
            }
        }
        return List.copyOf(compacted);
    }

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

    private void requireObject(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("compact summary must be a JSON object");
        }
    }

    private void requireText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isTextual()) {
            throw new IllegalStateException("compact summary field missing or invalid: " + fieldName);
        }
    }

    private void requireArray(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isArray()) {
            throw new IllegalStateException("compact summary field missing or invalid: " + fieldName);
        }
    }

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

    private int totalTokens(List<LlmMessage> messages) {
        return messages.stream()
                .map(LlmMessage::content)
                .mapToInt(tokenEstimator::estimate)
                .sum();
    }

    private void assertWithinHardTokenCap(List<LlmMessage> messages) {
        int total = totalTokens(messages);
        int hardCap = properties.getAgentLoop().getHardTokenCap();
        if (total > hardCap) {
            throw new IllegalStateException("compacted context exceeds hard token cap: total="
                    + total + ", cap=" + hardCap);
        }
    }

    private int thresholdTokens() {
        return properties.getContext().getSummaryCompactThresholdTokens();
    }

    private int recentBudgetTokens() {
        return properties.getContext().getRecentMessageBudgetTokens();
    }

    private record Block(List<LlmMessage> messages) {
        private int tokens(TokenEstimator tokenEstimator) {
            return messages.stream()
                    .map(LlmMessage::content)
                    .mapToInt(tokenEstimator::estimate)
                    .sum();
        }
    }
}
