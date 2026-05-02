package com.ai.agent.skill.core;

import java.util.regex.Pattern;

/**
 * 技能名称验证工具。
 * 提供技能名称格式验证功能，确保名称符合规范。
 */
public final class SkillNames {
    /** 技能名称正则模式：小写字母、数字开头，可包含连字符 */
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private SkillNames() {
    }

    /**
     * 验证技能名称格式是否有效。
     *
     * @param value 待验证的名称
     * @return 如果格式有效则返回true
     */
    public static boolean isValid(String value) {
        return value != null && SKILL_NAME.matcher(value).matches();
    }
}
