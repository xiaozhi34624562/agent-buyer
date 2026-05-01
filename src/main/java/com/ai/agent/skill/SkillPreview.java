package com.ai.agent.skill;

public record SkillPreview(String name, String description) {
    public SkillPreview {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill preview name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("skill preview description must not be blank");
        }
    }
}
