package com.ai.agent.tool.core;

import java.time.Duration;
import java.util.List;

/**
 * 工具Schema定义，描述工具的元信息和执行约束。
 *
 * <p>Schema定义工具名称、描述、参数规范、并发性、幂等性、超时时间等核心属性，
 * 是工具注册和调用的基础配置。
 */
public record ToolSchema(
        /** 工具名称，唯一标识工具 */
        String name,
        /** 工具描述，用于LLM理解工具用途 */
        String description,
        /** 参数JSON Schema，定义工具参数的结构和类型约束 */
        String parametersJsonSchema,
        /** 是否支持并发执行 */
        boolean isConcurrent,
        /** 是否幂等，幂等操作可安全重试 */
        boolean idempotent,
        /** 执行超时时间 */
        Duration timeout,
        /** 结果最大字节限制 */
        int maxResultBytes,
        /** 敏感字段列表，结果输出时需脱敏处理 */
        List<String> sensitiveFields
) {
    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name is required");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("tool description is required");
        }
        if (parametersJsonSchema == null || parametersJsonSchema.isBlank()) {
            throw new IllegalArgumentException("parametersJsonSchema is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("positive timeout is required");
        }
        if (maxResultBytes <= 0) {
            throw new IllegalArgumentException("positive maxResultBytes is required");
        }
        sensitiveFields = sensitiveFields == null ? List.of() : List.copyOf(sensitiveFields);
    }
}
