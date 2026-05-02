package com.ai.agent.trajectory.query;

/**
 * 轨迹查询服务。
 * <p>
 * 提供轨迹数据的查询和脱敏能力，将持久化实体转换为传输对象。
 * </p>
 */

import com.ai.agent.persistence.entity.AgentContextCompactionEntity;
import com.ai.agent.persistence.entity.AgentEventEntity;
import com.ai.agent.persistence.entity.AgentLlmAttemptEntity;
import com.ai.agent.persistence.entity.AgentMessageEntity;
import com.ai.agent.persistence.entity.AgentRunEntity;
import com.ai.agent.persistence.entity.AgentToolCallTraceEntity;
import com.ai.agent.persistence.entity.AgentToolProgressEntity;
import com.ai.agent.persistence.entity.AgentToolResultTraceEntity;
import com.ai.agent.trajectory.dto.AgentRunTrajectoryDto;
import com.ai.agent.trajectory.dto.CompactionDto;
import com.ai.agent.trajectory.dto.EventDto;
import com.ai.agent.trajectory.dto.LlmAttemptDto;
import com.ai.agent.trajectory.dto.MessageDto;
import com.ai.agent.trajectory.dto.MessageToolCallDto;
import com.ai.agent.trajectory.dto.RunDto;
import com.ai.agent.trajectory.dto.ToolCallDto;
import com.ai.agent.trajectory.dto.ToolProgressDto;
import com.ai.agent.trajectory.dto.ToolResultDto;
import com.ai.agent.trajectory.model.TrajectorySnapshot;
import com.ai.agent.trajectory.port.TrajectoryReader;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class TrajectoryQueryService {

    /** 字符串列表类型引用 */
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /** 最大预览字符数 */
    private static final int MAX_PREVIEW_CHARS = 512;

    /** 精确匹配的敏感字段名集合 */
    private static final Set<String> SENSITIVE_EXACT_NAMES = Set.of(
            "argsjson",
            "resultjson",
            "errorjson",
            "rawdiagnosticjson",
            "phone",
            "mobile",
            "email",
            "idcard",
            "cardno",
            "authorization",
            "password"
    );

    /** API Key 匹配模式 */
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");

    /** 确认令牌值匹配模式 */
    private static final Pattern CONFIRM_TOKEN_VALUE_PATTERN = Pattern.compile("ct_[A-Za-z0-9_-]+");

    /** 确认令牌文本匹配模式 */
    private static final Pattern CONFIRM_TOKEN_TEXT_PATTERN = Pattern.compile(
            "(?i)confirm[_\\- ]?token\\s*[:=]?\\s*[^\\s,;}]+"
    );

    /** 手机号匹配模式 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b1[3-9]\\d{9}\\b");

    /** 邮箱匹配模式 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    /** 轨迹读取器 */
    private final TrajectoryReader trajectoryReader;

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数。
     *
     * @param trajectoryReader 轨迹读取器
     * @param objectMapper      JSON 对象映射器
     */
    public TrajectoryQueryService(TrajectoryReader trajectoryReader, ObjectMapper objectMapper) {
        this.trajectoryReader = trajectoryReader;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取运行轨迹。
     *
     * @param runId 运行标识
     * @return 轨迹传输对象
     */
    public AgentRunTrajectoryDto getTrajectory(String runId) {
        TrajectorySnapshot snapshot = trajectoryReader.loadTrajectorySnapshot(runId);
        return new AgentRunTrajectoryDto(
                toRunDto(snapshot.run()),
                snapshot.messages().stream().map(this::toMessageDto).toList(),
                snapshot.llmAttempts().stream().map(this::toLlmAttemptDto).toList(),
                snapshot.toolCalls().stream().map(this::toToolCallDto).toList(),
                snapshot.toolResults().stream().map(this::toToolResultDto).toList(),
                snapshot.events().stream().map(this::toEventDto).toList(),
                snapshot.toolProgress().stream().map(this::toToolProgressDto).toList(),
                snapshot.compactions().stream().map(this::toCompactionDto).toList()
        );
    }

    /**
     * 转换运行实体为传输对象。
     *
     * @param entity 运行实体
     * @return 运行传输对象
     */
    private RunDto toRunDto(AgentRunEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RunDto(
                entity.getRunId(),
                entity.getStatus(),
                entity.getTurnNo(),
                entity.getParentRunId(),
                entity.getParentToolCallId(),
                firstNonBlank(entity.getAgentType(), "MAIN"),
                entity.getParentLinkStatus(),
                entity.getStartedAt(),
                entity.getUpdatedAt(),
                entity.getCompletedAt(),
                preview(entity.getLastError())
        );
    }

    /**
     * 转换消息实体为传输对象。
     *
     * @param entity 消息实体
     * @return 消息传输对象
     */
    private MessageDto toMessageDto(AgentMessageEntity entity) {
        return new MessageDto(
                entity.getMessageId(),
                entity.getSeq(),
                entity.getRole(),
                preview(entity.getContent()),
                entity.getToolUseId(),
                readMessageToolCalls(entity.getToolCalls()),
                entity.getCreatedAt()
        );
    }

    /**
     * 转换 LLM 调用实体为传输对象。
     *
     * @param entity LLM 调用实体
     * @return LLM 调用传输对象
     */
    private LlmAttemptDto toLlmAttemptDto(AgentLlmAttemptEntity entity) {
        return new LlmAttemptDto(
                entity.getAttemptId(),
                entity.getTurnNo(),
                entity.getProvider(),
                entity.getModel(),
                entity.getStatus(),
                entity.getFinishReason(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getTotalTokens(),
                preview(entity.getErrorJson()),
                entity.getStartedAt(),
                entity.getCompletedAt()
        );
    }

    /**
     * 转换工具调用实体为传输对象。
     *
     * @param entity 工具调用实体
     * @return 工具调用传输对象
     */
    private ToolCallDto toToolCallDto(AgentToolCallTraceEntity entity) {
        return new ToolCallDto(
                entity.getToolCallId(),
                entity.getMessageId(),
                entity.getSeq(),
                entity.getToolUseId(),
                entity.getToolName(),
                entity.getConcurrent(),
                entity.getIdempotent(),
                entity.getPrecheckFailed(),
                preview(entity.getPrecheckErrorJson()),
                entity.getCreatedAt()
        );
    }

    /**
     * 转换工具结果实体为传输对象。
     *
     * @param entity 工具结果实体
     * @return 工具结果传输对象
     */
    private ToolResultDto toToolResultDto(AgentToolResultTraceEntity entity) {
        return new ToolResultDto(
                entity.getResultId(),
                entity.getToolCallId(),
                entity.getToolUseId(),
                entity.getStatus(),
                entity.getSynthetic(),
                entity.getCancelReason(),
                preview(firstNonBlank(entity.getResultJson(), entity.getErrorJson())),
                entity.getCreatedAt()
        );
    }

    /**
     * 转换事件实体为传输对象。
     *
     * @param entity 事件实体
     * @return 事件传输对象
     */
    private EventDto toEventDto(AgentEventEntity entity) {
        return new EventDto(
                entity.getEventId(),
                entity.getEventType(),
                preview(entity.getPayloadJson()),
                entity.getCreatedAt()
        );
    }

    /**
     * 转换工具进度实体为传输对象。
     *
     * @param entity 工具进度实体
     * @return 工具进度传输对象
     */
    private ToolProgressDto toToolProgressDto(AgentToolProgressEntity entity) {
        return new ToolProgressDto(
                entity.getProgressId(),
                entity.getToolCallId(),
                entity.getStage(),
                preview(entity.getMessage()),
                entity.getPercent(),
                entity.getCreatedAt()
        );
    }

    /**
     * 转换压缩实体为传输对象。
     *
     * @param entity 压缩实体
     * @return 压缩传输对象
     */
    private CompactionDto toCompactionDto(AgentContextCompactionEntity entity) {
        return new CompactionDto(
                entity.getCompactionId(),
                entity.getTurnNo(),
                entity.getAttemptId(),
                entity.getStrategy(),
                entity.getBeforeTokens(),
                entity.getAfterTokens(),
                readStringList(entity.getCompactedMessageIds()),
                entity.getCreatedAt()
        );
    }

    /**
     * 读取消息中的工具调用列表。
     *
     * @param toolCallsJson 工具调用 JSON
     * @return 工具调用传输对象列表
     */
    private List<MessageToolCallDto> readMessageToolCalls(String toolCallsJson) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(toolCallsJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<MessageToolCallDto> toolCalls = new ArrayList<>();
            for (JsonNode item : root) {
                String toolUseId = text(item, "toolUseId");
                String toolName = firstNonBlank(text(item, "toolName"), text(item, "name"));
                if (toolUseId != null || toolName != null) {
                    toolCalls.add(new MessageToolCallDto(toolUseId, toolName));
                }
            }
            return List.copyOf(toolCalls);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * 读取字符串列表。
     *
     * @param json JSON 字符串
     * @return 字符串列表
     */
    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * 生成预览字符串，限制长度并脱敏。
     *
     * @param value 原始字符串
     * @return 预览字符串
     */
    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = sanitize(value);
        if (sanitized.length() <= MAX_PREVIEW_CHARS) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_PREVIEW_CHARS) + "...";
    }

    /**
     * 脱敏字符串内容。
     *
     * @param value 原始字符串
     * @return 脱敏后的字符串
     */
    private String sanitize(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode sanitized = sanitizeNode(root.deepCopy());
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            return sanitizeText(value);
        }
    }

    /**
     * 脱敏 JSON 节点。
     *
     * @param node JSON 节点
     * @return 脱敏后的节点
     */
    private JsonNode sanitizeNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            List<String> remove = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (isSensitiveName(entry.getKey())) {
                    remove.add(entry.getKey());
                } else {
                    object.set(entry.getKey(), sanitizeNode(entry.getValue()));
                }
            }
            object.remove(remove);
            return object;
        }
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, sanitizeNode(array.get(i)));
            }
            return array;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(sanitizeText(node.asText()));
        }
        return node;
    }

    /**
     * 判断字段名是否敏感。
     *
     * @param name 字段名
     * @return 是否敏感
     */
    private boolean isSensitiveName(String name) {
        String normalized = normalize(name);
        return SENSITIVE_EXACT_NAMES.contains(normalized)
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("apikey");
    }

    /**
     * 脱敏文本内容，替换敏感模式。
     *
     * @param value 原始文本
     * @return 脱敏后的文本
     */
    private String sanitizeText(String value) {
        String sanitized = CONFIRM_TOKEN_TEXT_PATTERN.matcher(value).replaceAll("[REDACTED]");
        sanitized = CONFIRM_TOKEN_VALUE_PATTERN.matcher(sanitized).replaceAll("ct_***");
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll("sk-***");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("***");
        return EMAIL_PATTERN.matcher(sanitized).replaceAll("***");
    }

    /**
     * 标准化字段名，用于敏感判断。
     *
     * @param value 原始字段名
     * @return 标准化后的字段名
     */
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * 从 JSON 节点获取文本字段值。
     *
     * @param node      JSON 节点
     * @param fieldName 字段名
     * @return 文本值
     */
    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param first  第一个字符串
     * @param second 第二个字符串
     * @return 非空字符串
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
