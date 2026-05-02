package com.ai.agent.skill.command;

import com.ai.agent.llm.model.LlmMessage;

import java.util.List;

/**
 * 技能命令解析结果。
 * 包含解析产生的注入消息、技能名称列表和总token数。
 *
 * @param messages   注入的消息列表
 * @param skillNames 匹配的技能名称列表
 * @param totalTokens 总token数
 */
public record SkillCommandResolution(
        List<LlmMessage> messages,
        List<String> skillNames,
        int totalTokens
) {
    /**
     * 紧凑构造器，确保列表非空。
     */
    public SkillCommandResolution {
        messages = messages == null ? List.of() : List.copyOf(messages);
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
    }

    /**
     * 创建空的解析结果。
     *
     * @return 空结果
     */
    public static SkillCommandResolution empty() {
        return new SkillCommandResolution(List.of(), List.of(), 0);
    }
}
