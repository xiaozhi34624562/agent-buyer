package com.ai.agent.tool.core;

/**
 * 工具参数验证结果，表示验证是否通过及相关信息。
 *
 * <p>验证通过时包含规范化后的参数JSON，验证失败时包含错误信息JSON。
 */
public record ToolValidation(
        /** 是否验证通过 */
        boolean accepted,
        /** 规范化后的参数JSON，验证通过时有效 */
        String normalizedArgsJson,
        /** 错误信息JSON，验证失败时有效 */
        String errorJson
) {
    /**
     * 创建验证通过的结果。
     *
     * @param normalizedArgsJson 规范化后的参数JSON
     * @return 验证通过的结果实例
     */
    public static ToolValidation accepted(String normalizedArgsJson) {
        return new ToolValidation(true, normalizedArgsJson, null);
    }

    /**
     * 创建验证拒绝的结果。
     *
     * @param errorJson 错误信息JSON
     * @return 验证拒绝的结果实例
     */
    public static ToolValidation rejected(String errorJson) {
        return new ToolValidation(false, null, errorJson);
    }
}
