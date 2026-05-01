package com.ai.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.agent.skill.SkillPathResolver;
import com.ai.agent.skill.SkillRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(AgentProperties.class)
public class AppConfig {
    @Bean
    ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @Qualifier("toolExecutor")
    ExecutorService toolExecutor(AgentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("tool-exec-");
        executor.setCorePoolSize(properties.getExecutor().getCorePoolSize());
        executor.setMaxPoolSize(properties.getExecutor().getMaxPoolSize());
        executor.setQueueCapacity(properties.getExecutor().getQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    @Bean
    @Qualifier("agentExecutor")
    ExecutorService agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-loop-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(128);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    @Bean
    @Qualifier("subAgentExecutor")
    ExecutorService subAgentExecutor(AgentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("sub-agent-");
        executor.setCorePoolSize(properties.getSubAgent().getExecutorCorePoolSize());
        executor.setMaxPoolSize(properties.getSubAgent().getExecutorMaxPoolSize());
        executor.setQueueCapacity(properties.getSubAgent().getExecutorQueueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    @Bean
    @Qualifier("eventExecutor")
    ExecutorService eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("event-writer-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8192);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor.getThreadPoolExecutor();
    }

    @Bean
    @Qualifier("sseScheduler")
    ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("sse-ping-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    String workerId() {
        return "jvm-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    @Bean
    SkillRegistry skillRegistry(AgentProperties properties, ResourceLoader resourceLoader) {
        return new SkillRegistry(
                skillsRoot(properties, resourceLoader),
                properties.getSkills().getEnabledSkillNames()
        );
    }

    @Bean
    SkillPathResolver skillPathResolver(AgentProperties properties, ResourceLoader resourceLoader) {
        return new SkillPathResolver(skillsRoot(properties, resourceLoader));
    }

    private Path skillsRoot(AgentProperties properties, ResourceLoader resourceLoader) {
        String rootPath = properties.getSkills().getRootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalStateException("agent.skills.root-path is required");
        }
        if (!rootPath.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
            return Path.of(rootPath);
        }
        Resource resource = resourceLoader.getResource(rootPath);
        try {
            if (resource.exists() && resource.isFile()) {
                return resource.getFile().toPath();
            }
            return materializeClasspathSkills(rootPath, properties.getSkills().getEnabledSkillNames(), resourceLoader);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to resolve classpath skills root", e);
        }
    }

    private Path materializeClasspathSkills(
            String rootPath,
            java.util.List<String> enabledSkillNames,
            ResourceLoader resourceLoader
    ) throws IOException {
        Path targetRoot = Files.createTempDirectory("agent-buyer-skills-");
        for (String skillName : enabledSkillNames) {
            Path targetDir = Files.createDirectories(targetRoot.resolve(skillName));
            Resource skillMarkdown = resourceLoader.getResource(rootPath + "/" + skillName + "/SKILL.md");
            if (!skillMarkdown.exists()) {
                throw new IllegalStateException("classpath skill is missing: " + skillName);
            }
            try (InputStream input = skillMarkdown.getInputStream()) {
                Files.copy(input, targetDir.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return targetRoot;
    }
}
