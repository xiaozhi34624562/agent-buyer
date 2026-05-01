package com.ai.agent.skill.command;

import com.ai.agent.config.AgentProperties;
import com.ai.agent.llm.context.TokenEstimator;
import com.ai.agent.llm.model.LlmMessage;
import com.ai.agent.llm.model.MessageRole;
import com.ai.agent.skill.core.SkillRegistry;
import com.ai.agent.skill.path.SkillPathResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class SkillCommandResolver {
    private static final Pattern SLASH_SKILL = Pattern.compile("(?<!\\S)/([a-z0-9][a-z0-9-]*)\\b");

    private final AgentProperties properties;
    private final SkillRegistry skillRegistry;
    private final SkillPathResolver skillPathResolver;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public SkillCommandResolver(
            AgentProperties properties,
            SkillRegistry skillRegistry,
            SkillPathResolver skillPathResolver,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.skillRegistry = skillRegistry;
        this.skillPathResolver = skillPathResolver;
        this.objectMapper = objectMapper;
    }

    public SkillCommandResolution resolve(List<LlmMessage> messages) {
        LlmMessage latestUserMessage = latestUserMessage(messages);
        if (latestUserMessage == null) {
            return SkillCommandResolution.empty();
        }
        List<String> skillNames = slashSkillNames(latestUserMessage.content());
        if (skillNames.isEmpty()) {
            return SkillCommandResolution.empty();
        }
        int maxPerMessage = properties.getSkills().getMaxPerMessage();
        if (skillNames.size() > maxPerMessage) {
            throw budgetExceeded(skillNames, maxPerMessage, skillNames.size());
        }
        List<String> documents = new ArrayList<>();
        int totalTokens = 0;
        for (String skillName : skillNames) {
            if (!skillRegistry.contains(skillName)) {
                throw new SkillCommandException("SKILL_NOT_FOUND", "slash skill is not available: " + skillName);
            }
            String content = skillPathResolver.view(skillName, null);
            String document = "<skill name=\"" + skillName + "\">\n" + content + "\n</skill>";
            documents.add(document);
            totalTokens += tokenEstimator.estimate(document);
        }
        int tokenBudget = properties.getSkills().getMaxTokenPerMessage();
        if (totalTokens > tokenBudget) {
            throw budgetExceeded(skillNames, tokenBudget, totalTokens);
        }
        String injectedContent = """
                The user requested the following trusted skill documents with slash commands. Use them as task guidance for this turn.

                %s
                """.formatted(String.join("\n\n", documents)).trim();
        return new SkillCommandResolution(
                List.of(new LlmMessage(
                        "transient-skills-" + latestUserMessage.messageId(),
                        MessageRole.USER,
                        injectedContent,
                        List.of(),
                        null,
                        Map.of("transientSkill", true, "skillNames", skillNames)
                )),
                skillNames,
                totalTokens
        );
    }

    private LlmMessage latestUserMessage(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            LlmMessage message = messages.get(index);
            if (message.role() == MessageRole.USER) {
                return message;
            }
        }
        return null;
    }

    private List<String> slashSkillNames(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = SLASH_SKILL.matcher(content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return List.copyOf(names);
    }

    private SkillCommandException budgetExceeded(List<String> skillNames, int budget, int actual) {
        int exceeded = Math.max(0, actual - budget);
        Map<String, Object> details = Map.of(
                "code", "SKILL_BUDGET_EXCEEDED",
                "matchedSkills", skillNames,
                "budget", budget,
                "actual", actual,
                "exceeded", exceeded
        );
        try {
            return new SkillCommandException(
                    "SKILL_BUDGET_EXCEEDED",
                    objectMapper.writeValueAsString(details),
                    details
            );
        } catch (JsonProcessingException e) {
            return new SkillCommandException("SKILL_BUDGET_EXCEEDED", "slash skill budget exceeded", details);
        }
    }
}
