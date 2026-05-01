package com.ai.agent.skill;

public class SkillRegistryException extends RuntimeException {
    private final SkillRegistryErrorCode code;

    public SkillRegistryException(SkillRegistryErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SkillRegistryErrorCode code() {
        return code;
    }
}
