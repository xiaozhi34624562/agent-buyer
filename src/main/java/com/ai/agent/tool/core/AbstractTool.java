package com.ai.agent.tool.core;

import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.PiiMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public abstract class AbstractTool implements Tool {
    private final PiiMasker piiMasker;
    protected final ObjectMapper objectMapper;

    protected AbstractTool(PiiMasker piiMasker, ObjectMapper objectMapper) {
        this.piiMasker = piiMasker;
        this.objectMapper = objectMapper;
    }

    @Override
    public final ToolTerminal run(ToolExecutionContext ctx, StartedTool running, CancellationToken token) throws Exception {
        ToolValidation validation = validate(
                new ToolUseContext(ctx.runId(), ctx.userId()),
                new ToolUse(running.call().toolUseId(), running.call().rawToolName(), running.call().argsJson())
        );
        if (!validation.accepted()) {
            return ToolTerminal.failed(running.call().toolCallId(), validation.errorJson());
        }
        ToolTerminal terminal = doRun(ctx, running, validation.normalizedArgsJson(), token);
        return enforceOutputPolicy(running.call().toolCallId(), terminal);
    }

    protected abstract ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws Exception;

    private ToolTerminal enforceOutputPolicy(String toolCallId, ToolTerminal terminal) {
        String resultJson = terminal.resultJson();
        if (resultJson != null) {
            if (resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > schema().maxResultBytes()) {
                return ToolTerminal.failed(
                        toolCallId,
                        "{\"type\":\"tool_result_too_large\",\"message\":\"tool result exceeds maxResultBytes\"}"
                );
            }
            resultJson = piiMasker.maskJson(resultJson, schema().sensitiveFields());
        }
        String errorJson = piiMasker.maskJson(terminal.errorJson(), schema().sensitiveFields());
        return new ToolTerminal(
                terminal.toolCallId(),
                terminal.status(),
                resultJson,
                errorJson,
                terminal.cancelReason(),
                terminal.synthetic()
        );
    }

    /**
     * Returns empty JSON object if argsJson is null or blank.
     */
    protected String defaultJson(String argsJson) {
        return argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
    }

    /**
     * Creates a terminal for cancellation before side effect was applied.
     */
    protected ToolTerminal cancelledBeforeSideEffect(StartedTool running) {
        return ToolTerminal.syntheticCancelled(
                running.call().toolCallId(),
                CancelReason.RUN_ABORTED,
                "{\"type\":\"run_aborted\",\"message\":\"tool cancelled before side effect\"}"
        );
    }

    /**
     * Creates a terminal for cancellation before side effect with custom message.
     */
    protected ToolTerminal cancelledBeforeSideEffect(StartedTool running, String message) {
        return ToolTerminal.syntheticCancelled(
                running.call().toolCallId(),
                CancelReason.RUN_ABORTED,
                errorJson("run_aborted", message)
        );
    }

    /**
     * Creates an error JSON string.
     */
    protected String errorJson(String type, String message) {
        return toJson(Map.of("type", type, "message", message == null ? "" : message));
    }

    /**
     * Serializes a value to JSON string.
     */
    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize json", e);
        }
    }
}
