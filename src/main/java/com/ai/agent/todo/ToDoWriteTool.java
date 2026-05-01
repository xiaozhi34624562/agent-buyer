package com.ai.agent.todo;

import com.ai.agent.api.ToolProgressEvent;
import com.ai.agent.tool.AbstractTool;
import com.ai.agent.tool.CancelReason;
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
public final class ToDoWriteTool extends AbstractTool {
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "stepId": {"type": "string"},
                "status": {
                  "type": "string",
                  "enum": ["PENDING", "IN_PROGRESS", "DONE", "BLOCKED", "CANCELLED"]
                },
                "notes": {"type": "string"}
              },
              "required": ["stepId", "status"],
              "additionalProperties": false
            }
            """;

    private final TodoStore store;
    private final ObjectMapper objectMapper;
    private final TrajectoryWriter trajectoryWriter;

    @Autowired
    public ToDoWriteTool(
            PiiMasker piiMasker,
            TodoStore store,
            ObjectMapper objectMapper,
            ObjectProvider<TrajectoryWriter> trajectoryWriterProvider
    ) {
        this(piiMasker, store, objectMapper, trajectoryWriterProvider.getIfAvailable());
    }

    public ToDoWriteTool(PiiMasker piiMasker, TodoStore store, ObjectMapper objectMapper, TrajectoryWriter trajectoryWriter) {
        super(piiMasker);
        this.store = store;
        this.objectMapper = objectMapper;
        this.trajectoryWriter = trajectoryWriter;
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "todo_write",
                "Update one ToDo step in the agent's short-lived plan for the current run.",
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
            WriteArgs args = objectMapper.readValue(defaultJson(use.argsJson()), WriteArgs.class);
            if (args.stepId() == null || args.stepId().isBlank()) {
                return ToolValidation.rejected(error("missing_step_id", "stepId is required"));
            }
            if (args.status() == null || args.status().isBlank()) {
                return ToolValidation.rejected(error("missing_status", "status is required"));
            }
            parseStatus(args.status());
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (IllegalArgumentException e) {
            return ToolValidation.rejected(error("invalid_status", e.getMessage()));
        } catch (Exception e) {
            return ToolValidation.rejected(error("invalid_args", e.getMessage()));
        }
    }

    @Override
    protected ToolTerminal doRun(ToolExecutionContext ctx, StartedTool running, String normalizedArgsJson, CancellationToken token) throws Exception {
        WriteArgs args = objectMapper.readValue(normalizedArgsJson, WriteArgs.class);
        TodoStatus status = parseStatus(args.status());
        ctx.sink().onToolProgress(new ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "updating", "正在更新 ToDo 状态", 50));
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running);
        }
        TodoStep step = store.updateStep(ctx.runId(), args.stepId(), status, args.notes());
        writeEvent(ctx.runId(), "todo_updated", Map.of(
                "toolCallId", running.call().toolCallId(),
                "stepId", step.stepId(),
                "status", step.status().name()
        ));
        ctx.sink().onToolProgress(new ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "done", "ToDo 状态已更新", 100));
        return ToolTerminal.succeeded(running.call().toolCallId(), objectMapper.writeValueAsString(Map.of(
                "step", step
        )));
    }

    private TodoStatus parseStatus(String status) {
        return TodoStatus.valueOf(status);
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
                "{\"type\":\"run_aborted\",\"message\":\"todo write cancelled before side effect\"}"
        );
    }

    private record WriteArgs(String stepId, String status, String notes) {
    }
}
