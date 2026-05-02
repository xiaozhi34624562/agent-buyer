package com.ai.agent.todo.tool;

import com.ai.agent.todo.model.TodoDraft;
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

/**
 * ToDo创建工具。
 * 创建或替换Agent的短期任务计划，用于跟踪当前运行中的待办事项。
 */
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
        super(piiMasker, objectMapper);
        this.store = store;
        this.trajectoryWriter = trajectoryWriter;
    }

    /**
     * 获取工具Schema定义。
     *
     * @return 工具Schema
     */
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

    /**
     * 验证工具参数。
     *
     * @param ctx  工具使用上下文
     * @param use  工具使用请求
     * @return 验证结果
     */
    @Override
    public ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        try {
            CreateArgs args = objectMapper.readValue(defaultJson(use.argsJson()), CreateArgs.class);
            if (args.items() == null || args.items().isEmpty()) {
                return ToolValidation.rejected(errorJson("missing_items", "items is required"));
            }
            for (TodoDraft item : args.items()) {
                if (item == null || item.title() == null || item.title().isBlank()) {
                    return ToolValidation.rejected(errorJson("missing_title", "each item title is required"));
                }
            }
            return ToolValidation.accepted(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            return ToolValidation.rejected(errorJson("invalid_args", e.getMessage()));
        }
    }

    /**
     * 执行ToDo创建操作。
     *
     * @param ctx              工具执行上下文
     * @param running          已启动的工具实例
     * @param normalizedArgsJson 标准化后的参数JSON
     * @param token            取消令牌
     * @return 工具执行结果
     */
    @Override
    protected ToolTerminal doRun(ToolExecutionContext ctx, StartedTool running, String normalizedArgsJson, CancellationToken token) throws Exception {
        CreateArgs args = objectMapper.readValue(normalizedArgsJson, CreateArgs.class);
        ctx.sink().onToolProgress(new ToolProgressEvent(ctx.runId(), running.call().toolCallId(), "planning", "正在创建 ToDo 计划", 40));
        if (token.isCancellationRequested()) {
            return cancelledBeforeSideEffect(running, "todo create cancelled before side effect");
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

    private record CreateArgs(List<TodoDraft> items) {
    }
}
