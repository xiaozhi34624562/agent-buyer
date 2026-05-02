package com.ai.agent.skill.path;

/**
 * 技能路径错误码枚举。
 * 定义技能路径解析过程中可能发生的错误类别。
 */
public enum SkillPathErrorCode {
    /** 技能根目录不可用 */
    SKILLS_ROOT_UNAVAILABLE,
    /** 技能未找到 */
    SKILL_NOT_FOUND,
    /** 技能名称无效 */
    INVALID_SKILL_NAME,
    /** 路径越界 */
    PATH_ESCAPE,
    /** 文件未找到 */
    FILE_NOT_FOUND,
    /** 文件读取失败 */
    FILE_READ_FAILED
}
