package com.ai.agent.skill;

import com.ai.agent.llm.LlmMessage;

import java.util.List;

public record SkillCommandResolution(
        List<LlmMessage> messages,
        List<String> skillNames,
        int totalTokens
) {
    public SkillCommandResolution {
        messages = messages == null ? List.of() : List.copyOf(messages);
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
    }

    public static SkillCommandResolution empty() {
        return new SkillCommandResolution(List.of(), List.of(), 0);
    }
}
