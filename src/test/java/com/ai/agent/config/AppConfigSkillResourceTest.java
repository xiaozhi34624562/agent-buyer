package com.ai.agent.config;

import com.ai.agent.skill.path.SkillPathResolver;
import com.ai.agent.skill.core.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigSkillResourceTest {
    @Test
    void defaultClasspathSkillsCanStartWithoutRepositoryWorkingDirectory() {
        AgentProperties properties = new AgentProperties();
        AppConfig appConfig = new AppConfig();
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

        SkillRegistry registry = appConfig.skillRegistry(properties, resourceLoader);
        SkillPathResolver resolver = appConfig.skillPathResolver(properties, resourceLoader);

        assertThat(registry.previews()).extracting("name")
                .containsExactly("purchase-guide", "return-exchange-guide", "order-issue-support");
        assertThat(resolver.view("purchase-guide", null)).contains("# Purchase Guide");
    }
}
