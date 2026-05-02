package com.ai.agent.skill.command;

import java.util.Map;

/**
 * 技能命令异常。
 * 表示技能命令解析过程中发生的错误，包含错误码和详细信息。
 */
public class SkillCommandException extends RuntimeException {
    private final String code;
    private final Map<String, Object> details;

    /**
     * 创建技能命令异常。
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public SkillCommandException(String code, String message) {
        this(code, message, Map.of());
    }

    /**
     * 创建带详细信息的技能命令异常。
     *
     * @param code    错误码
     * @param message 错误消息
     * @param details 详细信息映射
     */
    public SkillCommandException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }

    /**
     * 获取详细信息。
     *
     * @return 详细信息映射
     */
    public Map<String, Object> details() {
        return details;
    }
}
