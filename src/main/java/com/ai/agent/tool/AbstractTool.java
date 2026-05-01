package com.ai.agent.tool;

public abstract class AbstractTool implements Tool {
    private final PiiMasker piiMasker;

    protected AbstractTool(PiiMasker piiMasker) {
        this.piiMasker = piiMasker;
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
}
