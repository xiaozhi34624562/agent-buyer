package com.ai.agent.todo;

import com.ai.agent.api.ToolProgressEvent;
import com.ai.agent.tool.CancelReason;
import com.ai.agent.tool.AbstractTool;
import com.ai.agent.tool.CancellationToken;
import com.ai.agent.tool.PiiMasker;
import com.ai.agent.tool.StartedTool;
import com.ai.agent.tool.ToolExecutionContext;
import com.ai.agent.tool.ToolSchema;
import com.ai.agent.tool.ToolTerminal;
import com.ai.agent.tool.ToolUse;
import com.ai.agent.tool.ToolUseContext;
import com.ai.agent.tool.ToolValidation;
import com.ai.agent.trajectory.TrajectoryWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public final class ToDoCreateTool extends AbstractTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "items": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "title": {"type": "string"},
                      "notes": {"type": "string"}
                    },
                    "required": ["title"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["items"],
              "additionalProperties": false
            }
            """;

    private final TodoStore store;
    private final ObjectMapper objectMapper;
    private final TrajectoryWriter trajectoryWriter;

    @Autowired
    public ToDoCreateTool(
            PiiMasker piiMasker,
            TodoStore store,
            ObjectMapper objectMapper,
            ObjectProvider<TrajectoryWriter> trajectoryWriterProvider
    ) {
        this(piiMasker, store, objectMapper, trajectoryWriterProvider.getIfAvailable());
    }

    public ToDoCreateTool(PiiMasker piiMasker, TodoStore store, ObjectMapper objectMapper, TrajectoryWriter trajectoryWriter) {
        super(piiMasker);
        this.store = store;
        this.objectMapper = objectMapper;
        this.trajectoryWriter = trajectoryWriter;
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "todo_create",
                "Create or replace the agent's short-lived ToDo plan for the current run. ToDo is working memory, not a business fact source.",
                SCHEMA,
                false,
                false,
                Duration.ofSeconds(5),
                16_384,
                List.of()
        );
    }

    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            CreateArgs args = objectMapper.readValue(defaultJson(use.argsJson()), CreateArgs.class);
            if (args.items() == null || args.items().isEmpty()) {
                return ToolValidation.rejected(error("missing_items", "items is required"));
            }
            for (TodoDraft item : args.items()) {
                if (item == null || item.title() == null || item.title().isBlank()) {
                    return ToolValidation.rejected(error("missing_title", "each item title is required"));
                }
            }
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return ToolValidation.rejected(error("invalid_args", e.getMessage()));
        }
    }

    @Override
    protected ToolTerminal doRun(ToolExecutionContext ctx, StartedTool running, String normalizedArgsJson, CancellationToken token) throws Exception {
        CreateArgs args = objectMapper.readValue(normalizedArgsJson, CreateArgs.class);
        ctx.sink().onToolProgress(new ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "planning", "正在创建 ToDo 计划", 40));
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running);
        }
        List<TodoStep> steps = store.replacePlan(ctx.runId(), args.items());
        writeEvent(ctx.runId(), "todo_created", Map.of(
                "toolCallId", running.call().toolCallId(),
                "count", steps.size()
        ));
        ctx.sink().onToolProgress(new ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "done", "ToDo 计划已创建", 100));
        return ToolTerminal.succeeded(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                "count", steps.size(),
                "steps", steps
        )));
    }

    private void writeEvent(String runId, String eventType, Map<String, Object> payload) {
        if (trajectoryWriter == null) {
            return;
        }
        trajectoryWriter.writeAgentEvent(runId, eventType, toJson(payload));
    }

    private String defaultJson(String argsJson) {
        return argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
    }

    private String error(String type, String message) {
        return toJson(Map.of("type", type, "message", message == null ? "" : message));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"serialization_error\"}";
        }
    }

    private ToolTerminal cancelledBeforeSideEffect(StartedTool running) {
        return ToolTerminal.syntheticCancelled(
                running.call().toolCallId(),
                CancelReason.RUN_ABORTED,
                "{\"type\":\"run_aborted\",\"message\":\"todo create cancelled before side effect\"}"
        );
    }

    private record CreateArgs(List<TodoDraft> items) {
    }
}
