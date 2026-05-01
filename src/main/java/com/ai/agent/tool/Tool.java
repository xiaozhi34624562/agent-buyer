package com.ai.agent.tool;

public interface Tool {
    ToolSchema schema();

    default ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        return ToolValidation.accepted(use.argsJson() == null || use.argsJson().isBlank() ? "{}" : use.argsJson());
    }

    default ToolTerminal run(ToolExecutionContext ctx, StartedTool running, CancellationToken token) throws Exception {
        throw new UnsupportedOperationException("tool run is not implemented: " + schema().name());
    }
}
