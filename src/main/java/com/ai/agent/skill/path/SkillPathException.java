package com.ai.agent.skill.path;

/**
 * 技能路径异常。
 * 表示技能路径解析过程中发生的错误，包含错误码用于分类处理。
 */
public class SkillPathException extends RuntimeException {
    private final SkillPathErrorCode code;

    /**
     * 创建技能路径异常。
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public SkillPathException(SkillPathErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public SkillPathErrorCode code() {
        return code;
    }
}
