package com.ai.agent.skill;

import java.util.regex.Pattern;

final class SkillNames {
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private SkillNames() {
    }

    static boolean isValid(String value) {
        return value != null && SKILL_NAME.matcher(value).matches();
    }
}
