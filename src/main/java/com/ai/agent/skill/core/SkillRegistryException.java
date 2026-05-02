package com.ai.agent.skill.core;

/**
 * 技能注册表异常。
 * 表示技能注册表操作过程中发生的错误，包含错误码用于分类处理。
 */
public class SkillRegistryException extends RuntimeException {
    private final SkillRegistryErrorCode code;

    /**
     * 创建技能注册表异常。
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public SkillRegistryException(SkillRegistryErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public SkillRegistryErrorCode code() {
        return code;
    }
}
