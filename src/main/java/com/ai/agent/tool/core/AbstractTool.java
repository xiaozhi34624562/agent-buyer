package com.ai.agent.tool.core;

import com.ai.agent.tool.model.CancelReason;
import com.ai.agent.tool.model.StartedTool;
import com.ai.agent.tool.model.ToolTerminal;
import com.ai.agent.tool.model.ToolUse;
import com.ai.agent.tool.security.PiiMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 抽象工具基类，提供工具执行的通用框架逻辑。
 *
 * <p>此类封装了参数验证、输出脱敏、结果大小限制等通用处理，
 * 子类只需实现{@link #doRun}方法定义具体执行逻辑。
 */
public abstract class AbstractTool implements Tool {
    private final PiiMasker piiMasker;
    protected final ObjectMapper objectMapper;

    protected AbstractTool(PiiMasker piiMasker, ObjectMapper objectMapper) {
        this.piiMasker = piiMasker;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行工具的模板方法，包含验证、执行和输出策略处理。
     *
     * <p>此方法不可覆盖，子类应覆盖{@link #doRun}方法实现具体逻辑。
     *
     * @param ctx 工具执行上下文
     * @param running 已启动的工具实例
     * @param token 取消令牌
     * @return 工具执行终态结果
     * @throws Exception 执行过程中发生的异常
     */
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

    /**
     * 执行具体工具逻辑，由子类实现。
     *
     * @param ctx 工具执行上下文
     * @param running 已启动的工具实例
     * @param normalizedArgsJson 规范化后的参数JSON
     * @param token 取消令牌
     * @return 工具执行终态结果
     * @throws Exception 执行过程中发生的异常
     */
    protected abstract ToolTerminal doRun(
            ToolExecutionContext ctx,
            StartedTool running,
            String normalizedArgsJson,
            CancellationToken token
    ) throws Exception;

    /**
     * 应用输出策略，包括大小限制和敏感信息脱敏。
     *
     * @param toolCallId 工具调用标识符
     * @param terminal 原始终态结果
     * @return 处理后的终态结果
     */
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
     * 返回空JSON对象如果参数为空。
     *
     * @param argsJson 原始参数JSON
     * @return 非空参数JSON字符串
     */
    protected String defaultJson(String argsJson) {
        return argsJson == null || argsJson.isBlank() ? "{}" : argsJson;
    }

    /**
     * 创建副作用应用前取消的终态结果。
     *
     * @param running 已启动的工具实例
     * @return 取消终态结果
     */
    protected ToolTerminal cancelledBeforeSideEffect(StartedTool running) {
        return ToolTerminal.syntheticCancelled(
                running.call().toolCallId(),
                CancelReason.RUN_ABORTED,
                "{\"type\":\"run_aborted\",\"message\":\"tool cancelled before side effect\"}"
        );
    }

    /**
     * 创建副作用应用前取消的终态结果，使用自定义消息。
     *
     * @param running 已启动的工具实例
     * @param message 自定义错误消息
     * @return 取消终态结果
     */
    protected ToolTerminal cancelledBeforeSideEffect(StartedTool running, String message) {
        return ToolTerminal.syntheticCancelled(
                running.call().toolCallId(),
                CancelReason.RUN_ABORTED,
                errorJson("run_aborted", message)
        );
    }

    /**
     * 创建错误JSON字符串。
     *
     * @param type 错误类型
     * @param message 错误消息
     * @return JSON格式的错误信息
     */
    protected String errorJson(String type, String message) {
        return toJson(Map.of("type", type, "message", message == null ? "" : message));
    }

    /**
     * 将对象序列化为JSON字符串。
     *
     * @param value 待序列化的对象
     * @return JSON字符串
     */
    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize json", e);
        }
    }
}
