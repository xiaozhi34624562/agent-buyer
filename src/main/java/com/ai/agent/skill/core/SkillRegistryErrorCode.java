package com.ai.agent.skill.core;

/**
 * 技能注册表错误码枚举。
 * 定义技能注册表操作过程中可能发生的错误类别。
 */
public enum SkillRegistryErrorCode {
    /** 技能根目录不可用 */
    SKILLS_ROOT_UNAVAILABLE,
    /** 技能文件读取失败 */
    SKILL_FILE_READ_FAILED,
    /** 技能路径越界 */
    SKILL_PATH_ESCAPE,
    /** 技能前置元数据缺失 */
    SKILL_FRONTMATTER_MISSING,
    /** 技能前置元数据无效 */
    SKILL_FRONTMATTER_INVALID
}
