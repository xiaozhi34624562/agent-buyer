package com.ai.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.agent.skill.path.SkillPathResolver;
import com.ai.agent.skill.core.SkillRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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

/**
 * 应用核心配置类。
 *
 * <p>配置Spring Boot应用的核心组件，包括JSON序列化、Redis连接、
 * 线程池执行器、技能注册等。提供Agent运行所需的基础设施Bean。
 *
 * @author ai-agent
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AgentProperties.class)
public class AppConfig {
    /**
     * 创建JSON对象映射器。
     *
     * <p>配置Jackson ObjectMapper，禁用未知属性反序列化失败特性，
     * 提高JSON解析的容错性。
     *
     * @param builder Jackson对象映射器构建器
     * @return 配置好的ObjectMapper实例
     */
    @Bean
    ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * 创建Redis字符串模板。
     *
     * <p>用于Redis的字符串键值操作，简化Redis数据访问。
     *
     * @param connectionFactory Redis连接工厂
     * @return StringRedisTemplate实例
     */
    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * 创建Redis消息监听容器。
     *
     * <p>用于订阅Redis消息通道，支持发布订阅模式的消息处理。
     *
     * @param connectionFactory Redis连接工厂
     * @return RedisMessageListenerContainer实例
     */
    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * 创建工具执行器。
     *
     * <p>用于执行Agent工具调用，线程池参数从配置属性读取。
     * 使用AbortPolicy作为拒绝策略，超出容量时直接抛出异常。
     *
     * @param properties Agent配置属性
     * @return 工具执行器线程池
     */
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

    /**
     * 创建Agent执行器。
     *
     * <p>用于执行Agent主循环任务，固定配置核心线程4个、最大线程16个、队列容量128。
     * 使用AbortPolicy作为拒绝策略。
     *
     * @return Agent执行器线程池
     */
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

    /**
     * 创建子Agent执行器。
     *
     * <p>用于执行子Agent任务，线程池参数从配置属性读取。
     * 使用AbortPolicy作为拒绝策略。
     *
     * @param properties Agent配置属性
     * @return 子Agent执行器线程池
     */
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

    /**
     * 创建事件执行器。
     *
     * <p>用于异步写入事件日志，单线程执行保证事件顺序，队列容量8192。
     * 使用AbortPolicy作为拒绝策略。
     *
     * @return 事件执行器线程池
     */
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

    /**
     * 创建SSE调度执行器。
     *
     * <p>用于SSE连接的心跳保活调度，创建2个守护线程。
     *
     * @return SSE调度执行器
     */
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

    /**
     * 创建工作节点标识。
     *
     * <p>使用JVM运行时名称作为唯一标识，格式为"jvm-{进程名}"。
     *
     * @return 工作节点标识字符串
     */
    @Bean
    String workerId() {
        return "jvm-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    /**
     * 创建技能注册表。
     *
     * <p>根据配置加载启用的技能，用于Agent运行时技能调用。
     *
     * @param properties Agent配置属性
     * @param resourceLoader 资源加载器
     * @return 技能注册表实例
     */
    @Bean
    SkillRegistry skillRegistry(AgentProperties properties, ResourceLoader resourceLoader) {
        return new SkillRegistry(
                skillsRoot(properties, resourceLoader),
                properties.getSkills().getEnabledSkillNames()
        );
    }

    /**
     * 创建技能路径解析器。
     *
     * <p>用于解析技能文件路径，支持从classpath或文件系统加载技能。
     *
     * @param properties Agent配置属性
     * @param resourceLoader 资源加载器
     * @return 技能路径解析器实例
     */
    @Bean
    SkillPathResolver skillPathResolver(AgentProperties properties, ResourceLoader resourceLoader) {
        return new SkillPathResolver(skillsRoot(properties, resourceLoader));
    }

    /**
     * 解析技能根目录路径。
     *
     * <p>支持classpath路径和文件系统路径。对于classpath路径，
     * 如果资源不能直接访问文件，会将技能文件复制到临时目录。
     *
     * @param properties Agent配置属性
     * @param resourceLoader 资源加载器
     * @return 技能根目录路径
     */
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

    /**
     * 将classpath技能文件复制到临时目录。
     *
     * <p>用于处理classpath资源无法直接访问文件的情况，
     * 将启用的技能SKILL.md文件复制到临时目录供运行时使用。
     *
     * @param rootPath 技能根路径
     * @param enabledSkillNames 启用的技能名称列表
     * @param resourceLoader 资源加载器
     * @return 临时技能根目录路径
     * @throws IOException 文件操作异常
     */
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