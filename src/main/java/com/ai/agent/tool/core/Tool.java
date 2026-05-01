package com.ai.agent.tool.core;

import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;

public interface Tool {
    ToolSchema schema();

    default ToolValidation validate(ToolUseContext ctx, ToolUse use) {
        return ToolValidation.accepted(use.argsJson() == null || use.argsJson().isBlank() ? "{}" : use.argsJson());
    }

    default ToolTerminal run(ToolExecutionContext ctx, StartedTool running, CancellationToken token) throws Exception {
        throw new UnsupportedOperationException("tool run is not implemented: " + schema().name());
    }
}
