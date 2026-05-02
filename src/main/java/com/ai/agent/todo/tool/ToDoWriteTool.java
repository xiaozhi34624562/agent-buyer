package com.ai.agent.todo.tool;

import com.ai.agent.todo.model.TodoStatus;
import com.ai.agent.todo.model.TodoStep;
import com.ai.agent.todo.runtime.TodoStore;
import com.ai.agent.tool.core.AbstractTool;
import com.ai.agent.tool.core.CancellationToken;
import com.ai.agent.tool.core.ToolExecutionContext;
import com.ai.agent.tool.core.ToolSchema;
import com.ai.agent.tool.core.ToolUseContext;
import com.ai.agent.tool.core.ToolValidation;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.PiiMasker;
import com.ai.agent.trajectory.port.TrajectoryWriter;
import com.ai.agent.web.sse.ToolProgressEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
        super(piiMasker, objectMapper);
        this.store = store;
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
                return ToolValidation.rejected(errorJson("missing_step_id", "stepId is required"));
            }
            if (args.status() == null || args.status().isBlank()) {
                return ToolValidation.rejected(errorJson("missing_status", "status is required"));
            }
            parseStatus(args.status());
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (IllegalArgumentException e) {
            return ToolValidation.rejected(errorJson("invalid_status", e.getMessage()));
        } catch (Exception e) {
            return ToolValidation.rejected(errorJson("invalid_args", e.getMessage()));
        }
    }

    @Override
    protected ToolTerminal doRun(ToolExecutionContext ctx, StartedTool running, String normalizedArgsJson, CancellationToken token) throws Exception {
        WriteArgs args = objectMapper.readValue(normalizedArgsJson, WriteArgs.class);
        TodoStatus status = parseStatus(args.status());
        ctx.sink().onToolProgress(new ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "updating", "正在更新 ToDo 状态", 50));
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running, "todo write cancelled before side effect");
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

    private record WriteArgs(String stepId, String status, String notes) {
    }
}
