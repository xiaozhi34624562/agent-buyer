package com.ai.agent.skill.core;

/**
 * 技能预览信息。
 * 包含技能的基本描述信息，用于列表展示。
 *
 * @param name        技能名称
 * @param description 技能描述
 */
public record SkillPreview(String name, String description) {
    /**
     * 紧凑构造器，验证必填字段。
     *
     * @throws IllegalArgumentException 如果名称或描述为空
     */
    public SkillPreview {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill preview name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("skill preview description must not be blank");
        }
    }
}
