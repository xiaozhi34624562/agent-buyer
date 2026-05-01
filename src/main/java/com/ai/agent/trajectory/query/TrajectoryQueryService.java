package com.ai.agent.trajectory.query;

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
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final int MAX_PREVIEW_CHARS = 512;
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
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern CONFIRM_TOKEN_VALUE_PATTERN = Pattern.compile("ct_[A-Za-z0-9_-]+");
    private static final Pattern CONFIRM_TOKEN_TEXT_PATTERN = Pattern.compile(
            "(?i)confirm[_\\- ]?token\\s*[:=]?\\s*[^\\s,;}]+"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    private final TrajectoryReader trajectoryReader;
    private final ObjectMapper objectMapper;

    public TrajectoryQueryService(TrajectoryReader trajectoryReader, ObjectMapper objectMapper) {
        this.trajectoryReader = trajectoryReader;
        this.objectMapper = objectMapper;
    }

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

    private EventDto toEventDto(AgentEventEntity entity) {
        return new EventDto(
                entity.getEventId(),
                entity.getEventType(),
                preview(entity.getPayloadJson()),
                entity.getCreatedAt()
        );
    }

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

    private String sanitize(String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode sanitized = sanitizeNode(root.deepCopy());
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            return sanitizeText(value);
        }
    }

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

    private boolean isSensitiveName(String name) {
        String normalized = normalize(name);
        return SENSITIVE_EXACT_NAMES.contains(normalized)
                || normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("apikey");
    }

    private String sanitizeText(String value) {
        String sanitized = CONFIRM_TOKEN_TEXT_PATTERN.matcher(value).replaceAll("[REDACTED]");
        sanitized = CONFIRM_TOKEN_VALUE_PATTERN.matcher(sanitized).replaceAll("ct_***");
        sanitized = API_KEY_PATTERN.matcher(sanitized).replaceAll("sk-***");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("***");
        return EMAIL_PATTERN.matcher(sanitized).replaceAll("***");
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }
}
