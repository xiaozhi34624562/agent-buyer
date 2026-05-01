package com.ai.agent.skill.path;

public class SkillPathException extends RuntimeException {
    private final SkillPathErrorCode code;

    public SkillPathException(SkillPathErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SkillPathErrorCode code() {
        return code;
    }
}
