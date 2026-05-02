package com.ai.agent.config;

import com.ai.agent.loop.AgentLoop;
import com.ai.agent.subagent.tool.AgentTool;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent配置属性类。
 *
 * <p>用于绑定配置文件中以"agent"为前缀的配置项，包含Agent运行所需的各种参数配置。
 * 支持配置Redis键前缀、默认允许的工具、并行度限制、租约时间、限流策略、LLM配置等。
 *
 * @author ai-agent
 */
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private String redisKeyPrefix = "agent";
    private List<String> defaultAllowedTools = List.of("query_order", "cancel_order");
    private int maxParallel = 6;
    private int maxScan = 256;
    private long leaseMs = 90_000;
    private Duration confirmationTtl = Duration.ofMinutes(10);
    private Reaper reaper = new Reaper();
    private AgentLoop agentLoop = new AgentLoop();
    private Executor executor = new Executor();
    private Llm llm = new Llm();
    private RateLimit rateLimit = new RateLimit();
    private RequestPolicy requestPolicy = new RequestPolicy();
    private Context context = new Context();
    private Skills skills = new Skills();
    private SubAgent subAgent = new SubAgent();
    private Runtime runtime = new Runtime();
    private Todo todo = new Todo();
    private Admin admin = new Admin();

    /**
     * 获取Redis键前缀。
     *
     * @return Redis键前缀
     */
    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    /**
     * 设置Redis键前缀。
     *
     * @param redisKeyPrefix Redis键前缀
     */
    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    /**
     * 获取默认允许的工具列表。
     *
     * @return 默认允许的工具名称列表
     */
    public List<String> getDefaultAllowedTools() {
        return defaultAllowedTools;
    }

    /**
     * 设置默认允许的工具列表。
     *
     * @param defaultAllowedTools 默认允许的工具名称列表
     */
    public void setDefaultAllowedTools(List<String> defaultAllowedTools) {
        this.defaultAllowedTools = defaultAllowedTools == null ? List.of() : List.copyOf(defaultAllowedTools);
    }

    /**
     * 获取最大并行数。
     *
     * @return 最大并行数
     */
    public int getMaxParallel() {
        return maxParallel;
    }

    /**
     * 设置最大并行数。
     *
     * @param maxParallel 最大并行数
     */
    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    /**
     * 获取最大扫描数量。
     *
     * @return 最大扫描数量
     */
    public int getMaxScan() {
        return maxScan;
    }

    /**
     * 设置最大扫描数量。
     *
     * @param maxScan 最大扫描数量
     */
    public void setMaxScan(int maxScan) {
        this.maxScan = maxScan;
    }

    /**
     * 获取租约时长（毫秒）。
     *
     * @return 租约时长，单位毫秒
     */
    public long getLeaseMs() {
        return leaseMs;
    }

    /**
     * 设置租约时长。
     *
     * @param leaseMs 租约时长，单位毫秒
     */
    public void setLeaseMs(long leaseMs) {
        this.leaseMs = leaseMs;
    }

    /**
     * 获取确认操作TTL时长。
     *
     * @return 确认操作的生存时间
     */
    public Duration getConfirmationTtl() {
        return confirmationTtl;
    }

    /**
     * 设置确认操作TTL时长。
     *
     * @param confirmationTtl 确认操作的生存时间
     */
    public void setConfirmationTtl(Duration confirmationTtl) {
        this.confirmationTtl = confirmationTtl;
    }

    /**
     * 获取清理器配置。
     *
     * @return 清理器配置对象
     */
    public Reaper getReaper() {
        return reaper;
    }

    /**
     * 设置清理器配置。
     *
     * @param reaper 清理器配置对象
     */
    public void setReaper(Reaper reaper) {
        this.reaper = reaper;
    }

    /**
     * 获取Agent循环配置。
     *
     * @return Agent循环配置对象
     */
    public AgentLoop getAgentLoop() {
        return agentLoop;
    }

    /**
     * 设置Agent循环配置。
     *
     * @param agentLoop Agent循环配置对象
     */
    public void setAgentLoop(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /**
     * 获取执行器配置。
     *
     * @return 执行器配置对象
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * 设置执行器配置。
     *
     * @param executor 执行器配置对象
     */
    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /**
     * 获取LLM配置。
     *
     * @return LLM配置对象
     */
    public Llm getLlm() {
        return llm;
    }

    /**
     * 设置LLM配置。
     *
     * @param llm LLM配置对象
     */
    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    /**
     * 获取限流配置。
     *
     * @return 限流配置对象
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * 设置限流配置。
     *
     * @param rateLimit 限流配置对象
     */
    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    /**
     * 获取请求策略配置。
     *
     * @return 请求策略配置对象
     */
    public RequestPolicy getRequestPolicy() {
        return requestPolicy;
    }

    /**
     * 设置请求策略配置。
     *
     * @param requestPolicy 请求策略配置对象
     */
    public void setRequestPolicy(RequestPolicy requestPolicy) {
        this.requestPolicy = requestPolicy;
    }

    /**
     * 获取上下文配置。
     *
     * @return 上下文配置对象
     */
    public Context getContext() {
        return context;
    }

    /**
     * 设置上下文配置。
     *
     * @param context 上下文配置对象
     */
    public void setContext(Context context) {
        this.context = context == null ? new Context() : context;
    }

    /**
     * 获取技能配置。
     *
     * @return 技能配置对象
     */
    public Skills getSkills() {
        return skills;
    }

    /**
     * 设置技能配置。
     *
     * @param skills 技能配置对象
     */
    public void setSkills(Skills skills) {
        this.skills = skills == null ? new Skills() : skills;
    }

    /**
     * 获取子Agent配置。
     *
     * @return 子Agent配置对象
     */
    public SubAgent getSubAgent() {
        return subAgent;
    }

    /**
     * 设置子Agent配置。
     *
     * @param subAgent 子Agent配置对象
     */
    public void setSubAgent(SubAgent subAgent) {
        this.subAgent = subAgent == null ? new SubAgent() : subAgent;
    }

    /**
     * 获取运行时配置。
     *
     * @return 运行时配置对象
     */
    public Runtime getRuntime() {
        return runtime;
    }

    /**
     * 设置运行时配置。
     *
     * @param runtime 运行时配置对象
     */
    public void setRuntime(Runtime runtime) {
        this.runtime = runtime == null ? new Runtime() : runtime;
    }

    /**
     * 获取待办事项配置。
     *
     * @return 待办事项配置对象
     */
    public Todo getTodo() {
        return todo;
    }

    /**
     * 设置待办事项配置。
     *
     * @param todo 待办事项配置对象
     */
    public void setTodo(Todo todo) {
        this.todo = todo == null ? new Todo() : todo;
    }

    /**
     * 获取管理员配置。
     *
     * @return 管理员配置对象
     */
    public Admin getAdmin() {
        return admin;
    }

    /**
     * 设置管理员配置。
     *
     * @param admin 管理员配置对象
     */
    public void setAdmin(Admin admin) {
        this.admin = admin == null ? new Admin() : admin;
    }

    /**
     * Agent循环配置类。
     *
     * <p>配置Agent执行循环的相关参数，包括最大轮次、超时时间、Token限制等。
     */
    public static class AgentLoop {
        private int maxTurns = 10;
        private long toolResultTimeoutMs = 90_000;
        private long runWallclockTimeoutMs = 300_000;
        private int hardTokenCap = 30_000;
        private int llmCallBudgetPerUserTurn = 30;
        private int subAgentLlmCallBudgetPerUserTurn = 30;
        private int runWideLlmCallBudget = 80;

        /**
         * 获取最大轮次。
         *
         * @return 最大轮次
         */
        public int getMaxTurns() {
            return maxTurns;
        }

        /**
         * 设置最大轮次。
         *
         * @param maxTurns 最大轮次
         */
        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        /**
         * 获取工具结果超时时间。
         *
         * @return 工具结果超时时间，单位毫秒
         */
        public long getToolResultTimeoutMs() {
            return toolResultTimeoutMs;
        }

        /**
         * 设置工具结果超时时间。
         *
         * @param toolResultTimeoutMs 工具结果超时时间，单位毫秒
         */
        public void setToolResultTimeoutMs(long toolResultTimeoutMs) {
            this.toolResultTimeoutMs = toolResultTimeoutMs;
        }

        /**
         * 获取运行总超时时间。
         *
         * @return 运行总超时时间，单位毫秒
         */
        public long getRunWallclockTimeoutMs() {
            return runWallclockTimeoutMs;
        }

        /**
         * 设置运行总超时时间。
         *
         * @param runWallclockTimeoutMs 运行总超时时间，单位毫秒
         */
        public void setRunWallclockTimeoutMs(long runWallclockTimeoutMs) {
            this.runWallclockTimeoutMs = runWallclockTimeoutMs;
        }

        /**
         * 获取硬性Token上限。
         *
         * @return 硬性Token上限
         */
        public int getHardTokenCap() {
            return hardTokenCap;
        }

        /**
         * 设置硬性Token上限。
         *
         * @param hardTokenCap 硬性Token上限
         */
        public void setHardTokenCap(int hardTokenCap) {
            this.hardTokenCap = hardTokenCap;
        }

        /**
         * 获取每用户轮次的LLM调用预算。
         *
         * @return 每用户轮次的LLM调用预算
         */
        public int getLlmCallBudgetPerUserTurn() {
            return llmCallBudgetPerUserTurn;
        }

        /**
         * 设置每用户轮次的LLM调用预算。
         *
         * @param llmCallBudgetPerUserTurn 每用户轮次的LLM调用预算
         */
        public void setLlmCallBudgetPerUserTurn(int llmCallBudgetPerUserTurn) {
            this.llmCallBudgetPerUserTurn = llmCallBudgetPerUserTurn;
        }

        /**
         * 获取每用户轮次的子Agent LLM调用预算。
         *
         * @return 每用户轮次的子Agent LLM调用预算
         */
        public int getSubAgentLlmCallBudgetPerUserTurn() {
            return subAgentLlmCallBudgetPerUserTurn;
        }

        /**
         * 设置每用户轮次的子Agent LLM调用预算。
         *
         * @param subAgentLlmCallBudgetPerUserTurn 每用户轮次的子Agent LLM调用预算
         */
        public void setSubAgentLlmCallBudgetPerUserTurn(int subAgentLlmCallBudgetPerUserTurn) {
            this.subAgentLlmCallBudgetPerUserTurn = subAgentLlmCallBudgetPerUserTurn;
        }

        /**
         * 获取运行级别的LLM调用预算。
         *
         * @return 运行级别的LLM调用预算
         */
        public int getRunWideLlmCallBudget() {
            return runWideLlmCallBudget;
        }

        /**
         * 设置运行级别的LLM调用预算。
         *
         * @param runWideLlmCallBudget 运行级别的LLM调用预算
         */
        public void setRunWideLlmCallBudget(int runWideLlmCallBudget) {
            this.runWideLlmCallBudget = runWideLlmCallBudget;
        }
    }

    /**
     * 清理器配置类。
     *
     * <p>配置过期运行清理任务的相关参数。
     */
    public static class Reaper {
        private boolean enabled = true;
        private long intervalMs = 10_000;

        /**
         * 判断清理器是否启用。
         *
         * @return 清理器是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置清理器是否启用。
         *
         * @param enabled 清理器是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取清理间隔时间。
         *
         * @return 清理间隔时间，单位毫秒
         */
        public long getIntervalMs() {
            return intervalMs;
        }

        /**
         * 设置清理间隔时间。
         *
         * @param intervalMs 清理间隔时间，单位毫秒
         */
        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    /**
     * 线程池执行器配置类。
     *
     * <p>配置Agent使用的线程池参数，包括核心线程数、最大线程数和队列容量。
     */
    public static class Executor {
        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 256;

        /**
         * 获取核心线程数。
         *
         * @return 核心线程数
         */
        public int getCorePoolSize() {
            return corePoolSize;
        }

        /**
         * 设置核心线程数。
         *
         * @param corePoolSize 核心线程数
         */
        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        /**
         * 获取最大线程数。
         *
         * @return 最大线程数
         */
        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        /**
         * 设置最大线程数。
         *
         * @param maxPoolSize 最大线程数
         */
        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        /**
         * 获取队列容量。
         *
         * @return 队列容量
         */
        public int getQueueCapacity() {
            return queueCapacity;
        }

        /**
         * 设置队列容量。
         *
         * @param queueCapacity 队列容量
         */
        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    /**
     * LLM配置类。
     *
     * <p>配置大语言模型相关参数，支持多个LLM提供商。
     */
    public static class Llm {
        private String provider = "deepseek";
        private DeepSeek deepseek = new DeepSeek();
        private Qwen qwen = new Qwen();

        /**
         * 获取当前使用的LLM提供商。
         *
         * @return LLM提供商名称
         */
        public String getProvider() {
            return provider;
        }

        /**
         * 设置当前使用的LLM提供商。
         *
         * @param provider LLM提供商名称
         */
        public void setProvider(String provider) {
            this.provider = provider;
        }

        /**
         * 获取DeepSeek配置。
         *
         * @return DeepSeek配置对象
         */
        public DeepSeek getDeepseek() {
            return deepseek;
        }

        /**
         * 设置DeepSeek配置。
         *
         * @param deepseek DeepSeek配置对象
         */
        public void setDeepseek(DeepSeek deepseek) {
            this.deepseek = deepseek;
        }

        /**
         * 获取通义千问配置。
         *
         * @return 通义千问配置对象
         */
        public Qwen getQwen() {
            return qwen;
        }

        /**
         * 设置通义千问配置。
         *
         * @param qwen 通义千问配置对象
         */
        public void setQwen(Qwen qwen) {
            this.qwen = qwen;
        }
    }

    /**
     * DeepSeek配置类。
     *
     * <p>配置DeepSeek LLM服务的连接参数。
     */
    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String defaultModel = "deepseek-reasoner";
        private Duration requestTimeout = Duration.ofSeconds(60);

        /**
         * 获取API基础URL。
         *
         * @return API基础URL
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置API基础URL。
         *
         * @param baseUrl API基础URL
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 获取API密钥。
         *
         * @return API密钥
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置API密钥。
         *
         * @param apiKey API密钥
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 获取默认模型名称。
         *
         * @return 默认模型名称
         */
        public String getDefaultModel() {
            return defaultModel;
        }

        /**
         * 设置默认模型名称。
         *
         * @param defaultModel 默认模型名称
         */
        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        /**
         * 获取请求超时时间。
         *
         * @return 请求超时时间
         */
        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        /**
         * 设置请求超时时间。
         *
         * @param requestTimeout 请求超时时间
         */
        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    /**
     * 通义千问配置类。
     *
     * <p>配置阿里云通义千问LLM服务的连接参数。
     */
    public static class Qwen {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String defaultModel = "qwen-plus";
        private Duration requestTimeout = Duration.ofSeconds(60);

        /**
         * 获取API基础URL。
         *
         * @return API基础URL
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 设置API基础URL。
         *
         * @param baseUrl API基础URL
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        /**
         * 获取API密钥。
         *
         * @return API密钥
         */
        public String getApiKey() {
            return apiKey;
        }

        /**
         * 设置API密钥。
         *
         * @param apiKey API密钥
         */
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        /**
         * 获取默认模型名称。
         *
         * @return 默认模型名称
         */
        public String getDefaultModel() {
            return defaultModel;
        }

        /**
         * 设置默认模型名称。
         *
         * @param defaultModel 默认模型名称
         */
        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        /**
         * 获取请求超时时间。
         *
         * @return 请求超时时间
         */
        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        /**
         * 设置请求超时时间。
         *
         * @param requestTimeout 请求超时时间
         */
        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    /**
     * 限流配置类。
     *
     * <p>配置用户级别的请求频率和Token使用限制。
     */
    public static class RateLimit {
        private int runsPerUserPerMinute = 5;
        private int tokensPerUserPerDay = 100_000;

        /**
         * 获取每用户每分钟允许的运行次数。
         *
         * @return 每用户每分钟允许的运行次数
         */
        public int getRunsPerUserPerMinute() {
            return runsPerUserPerMinute;
        }

        /**
         * 设置每用户每分钟允许的运行次数。
         *
         * @param runsPerUserPerMinute 每用户每分钟允许的运行次数
         */
        public void setRunsPerUserPerMinute(int runsPerUserPerMinute) {
            this.runsPerUserPerMinute = runsPerUserPerMinute;
        }

        /**
         * 获取每用户每天的Token限额。
         *
         * @return 每用户每天的Token限额
         */
        public int getTokensPerUserPerDay() {
            return tokensPerUserPerDay;
        }

        /**
         * 设置每用户每天的Token限额。
         *
         * @param tokensPerUserPerDay 每用户每天的Token限额
         */
        public void setTokensPerUserPerDay(int tokensPerUserPerDay) {
            this.tokensPerUserPerDay = tokensPerUserPerDay;
        }
    }

    /**
     * 请求策略配置类。
     *
     * <p>配置请求验证策略，包括消息数量限制、内容长度限制、模型参数限制等。
     */
    public static class RequestPolicy {
        private int maxMessages = 20;
        private int maxContentChars = 16_000;
        private int maxTotalContentChars = 50_000;
        private double minTemperature = 0.0;
        private double maxTemperature = 2.0;
        private int minMaxTokens = 1;
        private int maxMaxTokens = 30_000;
        private int minMaxTurns = 1;
        private int maxMaxTurns = 10;
        private List<String> allowedModels = List.of("deepseek-reasoner", "deepseek-chat");

        /**
         * 获取最大消息数量。
         *
         * @return 最大消息数量
         */
        public int getMaxMessages() {
            return maxMessages;
        }

        /**
         * 设置最大消息数量。
         *
         * @param maxMessages 最大消息数量
         */
        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        /**
         * 获取单条消息最大字符数。
         *
         * @return 单条消息最大字符数
         */
        public int getMaxContentChars() {
            return maxContentChars;
        }

        /**
         * 设置单条消息最大字符数。
         *
         * @param maxContentChars 单条消息最大字符数
         */
        public void setMaxContentChars(int maxContentChars) {
            this.maxContentChars = maxContentChars;
        }

        /**
         * 获取消息总内容最大字符数。
         *
         * @return 消息总内容最大字符数
         */
        public int getMaxTotalContentChars() {
            return maxTotalContentChars;
        }

        /**
         * 设置消息总内容最大字符数。
         *
         * @param maxTotalContentChars 消息总内容最大字符数
         */
        public void setMaxTotalContentChars(int maxTotalContentChars) {
            this.maxTotalContentChars = maxTotalContentChars;
        }

        /**
         * 获取最小温度参数。
         *
         * @return 最小温度参数
         */
        public double getMinTemperature() {
            return minTemperature;
        }

        /**
         * 设置最小温度参数。
         *
         * @param minTemperature 最小温度参数
         */
        public void setMinTemperature(double minTemperature) {
            this.minTemperature = minTemperature;
        }

        /**
         * 获取最大温度参数。
         *
         * @return 最大温度参数
         */
        public double getMaxTemperature() {
            return maxTemperature;
        }

        /**
         * 设置最大温度参数。
         *
         * @param maxTemperature 最大温度参数
         */
        public void setMaxTemperature(double maxTemperature) {
            this.maxTemperature = maxTemperature;
        }

        /**
         * 获取最小最大Token数。
         *
         * @return 最小最大Token数
         */
        public int getMinMaxTokens() {
            return minMaxTokens;
        }

        /**
         * 设置最小最大Token数。
         *
         * @param minMaxTokens 最小最大Token数
         */
        public void setMinMaxTokens(int minMaxTokens) {
            this.minMaxTokens = minMaxTokens;
        }

        /**
         * 获取最大Token数上限。
         *
         * @return 最大Token数上限
         */
        public int getMaxMaxTokens() {
            return maxMaxTokens;
        }

        /**
         * 设置最大Token数上限。
         *
         * @param maxMaxTokens 最大Token数上限
         */
        public void setMaxMaxTokens(int maxMaxTokens) {
            this.maxMaxTokens = maxMaxTokens;
        }

        /**
         * 获取最小最大轮次。
         *
         * @return 最小最大轮次
         */
        public int getMinMaxTurns() {
            return minMaxTurns;
        }

        /**
         * 设置最小最大轮次。
         *
         * @param minMaxTurns 最小最大轮次
         */
        public void setMinMaxTurns(int minMaxTurns) {
            this.minMaxTurns = minMaxTurns;
        }

        /**
         * 获取最大轮次上限。
         *
         * @return 最大轮次上限
         */
        public int getMaxMaxTurns() {
            return maxMaxTurns;
        }

        /**
         * 设置最大轮次上限。
         *
         * @param maxMaxTurns 最大轮次上限
         */
        public void setMaxMaxTurns(int maxMaxTurns) {
            this.maxMaxTurns = maxMaxTurns;
        }

        /**
         * 获取允许的模型列表。
         *
         * @return 允许的模型名称列表
         */
        public List<String> getAllowedModels() {
            return allowedModels;
        }

        /**
         * 设置允许的模型列表。
         *
         * @param allowedModels 允许的模型名称列表
         */
        public void setAllowedModels(List<String> allowedModels) {
            this.allowedModels = allowedModels == null ? List.of() : List.copyOf(allowedModels);
        }
    }

    /**
     * 上下文配置类。
     *
     * <p>配置Agent上下文管理相关参数，包括结果截断阈值和压缩策略参数。
     */
    public static class Context {
        private int largeResultThresholdTokens = 2_000;
        private int largeResultHeadTokens = 200;
        private int largeResultTailTokens = 200;
        private int microCompactThresholdTokens = 50_000;
        private int summaryCompactThresholdTokens = 30_000;
        private int recentMessageBudgetTokens = 2_000;
        private int summaryMaxTokens = 1_200;

        /**
         * 获取大结果阈值Token数。
         *
         * @return 大结果阈值Token数
         */
        public int getLargeResultThresholdTokens() {
            return largeResultThresholdTokens;
        }

        /**
         * 设置大结果阈值Token数。
         *
         * @param largeResultThresholdTokens 大结果阈值Token数
         */
        public void setLargeResultThresholdTokens(int largeResultThresholdTokens) {
            this.largeResultThresholdTokens = largeResultThresholdTokens;
        }

        /**
         * 获取大结果头部保留Token数。
         *
         * @return 大结果头部保留Token数
         */
        public int getLargeResultHeadTokens() {
            return largeResultHeadTokens;
        }

        /**
         * 设置大结果头部保留Token数。
         *
         * @param largeResultHeadTokens 大结果头部保留Token数
         */
        public void setLargeResultHeadTokens(int largeResultHeadTokens) {
            this.largeResultHeadTokens = largeResultHeadTokens;
        }

        /**
         * 获取大结果尾部保留Token数。
         *
         * @return 大结果尾部保留Token数
         */
        public int getLargeResultTailTokens() {
            return largeResultTailTokens;
        }

        /**
         * 设置大结果尾部保留Token数。
         *
         * @param largeResultTailTokens 大结果尾部保留Token数
         */
        public void setLargeResultTailTokens(int largeResultTailTokens) {
            this.largeResultTailTokens = largeResultTailTokens;
        }

        /**
         * 获取微压缩阈值Token数。
         *
         * @return 微压缩阈值Token数
         */
        public int getMicroCompactThresholdTokens() {
            return microCompactThresholdTokens;
        }

        /**
         * 设置微压缩阈值Token数。
         *
         * @param microCompactThresholdTokens 微压缩阈值Token数
         */
        public void setMicroCompactThresholdTokens(int microCompactThresholdTokens) {
            this.microCompactThresholdTokens = microCompactThresholdTokens;
        }

        /**
         * 获取摘要压缩阈值Token数。
         *
         * @return 摘要压缩阈值Token数
         */
        public int getSummaryCompactThresholdTokens() {
            return summaryCompactThresholdTokens;
        }

        /**
         * 设置摘要压缩阈值Token数。
         *
         * @param summaryCompactThresholdTokens 摘要压缩阈值Token数
         */
        public void setSummaryCompactThresholdTokens(int summaryCompactThresholdTokens) {
            this.summaryCompactThresholdTokens = summaryCompactThresholdTokens;
        }

        /**
         * 获取最近消息预算Token数。
         *
         * @return 最近消息预算Token数
         */
        public int getRecentMessageBudgetTokens() {
            return recentMessageBudgetTokens;
        }

        /**
         * 设置最近消息预算Token数。
         *
         * @param recentMessageBudgetTokens 最近消息预算Token数
         */
        public void setRecentMessageBudgetTokens(int recentMessageBudgetTokens) {
            this.recentMessageBudgetTokens = recentMessageBudgetTokens;
        }

        /**
         * 获取摘要最大Token数。
         *
         * @return 摘要最大Token数
         */
        public int getSummaryMaxTokens() {
            return summaryMaxTokens;
        }

        /**
         * 设置摘要最大Token数。
         *
         * @param summaryMaxTokens 摘要最大Token数
         */
        public void setSummaryMaxTokens(int summaryMaxTokens) {
            this.summaryMaxTokens = summaryMaxTokens;
        }
    }

    /**
     * 技能配置类。
     *
     * <p>配置Agent技能系统相关参数，包括技能路径、启用的技能列表和技能加载限制。
     */
    public static class Skills {
        private String rootPath = "classpath:skills";
        private List<String> enabledSkillNames = List.of(
                "purchase-guide",
                "return-exchange-guide",
                "order-issue-support"
        );
        private int maxPerMessage = 3;
        private int maxTokenPerMessage = 8_000;

        /**
         * 获取技能根路径。
         *
         * @return 技能根路径
         */
        public String getRootPath() {
            return rootPath;
        }

        /**
         * 设置技能根路径。
         *
         * @param rootPath 技能根路径
         */
        public void setRootPath(String rootPath) {
            this.rootPath = rootPath;
        }

        /**
         * 获取启用的技能名称列表。
         *
         * @return 启用的技能名称列表
         */
        public List<String> getEnabledSkillNames() {
            return enabledSkillNames;
        }

        /**
         * 设置启用的技能名称列表。
         *
         * @param enabledSkillNames 启用的技能名称列表
         */
        public void setEnabledSkillNames(List<String> enabledSkillNames) {
            this.enabledSkillNames = enabledSkillNames == null ? List.of() : List.copyOf(enabledSkillNames);
        }

        /**
         * 获取每条消息最大技能数。
         *
         * @return 每条消息最大技能数
         */
        public int getMaxPerMessage() {
            return maxPerMessage;
        }

        /**
         * 设置每条消息最大技能数。
         *
         * @param maxPerMessage 每条消息最大技能数
         */
        public void setMaxPerMessage(int maxPerMessage) {
            this.maxPerMessage = maxPerMessage;
        }

        /**
         * 获取每条消息最大Token数。
         *
         * @return 每条消息最大Token数
         */
        public int getMaxTokenPerMessage() {
            return maxTokenPerMessage;
        }

        /**
         * 设置每条消息最大Token数。
         *
         * @param maxTokenPerMessage 每条消息最大Token数
         */
        public void setMaxTokenPerMessage(int maxTokenPerMessage) {
            this.maxTokenPerMessage = maxTokenPerMessage;
        }
    }

    /**
     * 子Agent配置类。
     *
     * <p>配置子Agent创建和执行的相关参数，包括并发限制、超时设置和线程池配置。
     */
    public static class SubAgent {
        private int maxSpawnPerRun = 2;
        private int maxConcurrentPerRun = 1;
        private int spawnBudgetPerUserTurn = 2;
        private long waitTimeoutMs = 180_000;
        private int executorCorePoolSize = 2;
        private int executorMaxPoolSize = 8;
        private int executorQueueCapacity = 64;
        private String spawnSystemPromptHint = """
                Use AgentTool only when the task truly needs an independent child context. A single run can create at most 2 SubAgents; if the budget is exceeded, handle the task directly.
                """;

        /**
         * 获取每次运行最大创建子Agent数。
         *
         * @return 每次运行最大创建子Agent数
         */
        public int getMaxSpawnPerRun() {
            return maxSpawnPerRun;
        }

        /**
         * 设置每次运行最大创建子Agent数。
         *
         * @param maxSpawnPerRun 每次运行最大创建子Agent数
         */
        public void setMaxSpawnPerRun(int maxSpawnPerRun) {
            this.maxSpawnPerRun = maxSpawnPerRun;
        }

        /**
         * 获取每次运行最大并发子Agent数。
         *
         * @return 每次运行最大并发子Agent数
         */
        public int getMaxConcurrentPerRun() {
            return maxConcurrentPerRun;
        }

        /**
         * 设置每次运行最大并发子Agent数。
         *
         * @param maxConcurrentPerRun 每次运行最大并发子Agent数
         */
        public void setMaxConcurrentPerRun(int maxConcurrentPerRun) {
            this.maxConcurrentPerRun = maxConcurrentPerRun;
        }

        /**
         * 获取每用户轮次的创建预算。
         *
         * @return 每用户轮次的创建预算
         */
        public int getSpawnBudgetPerUserTurn() {
            return spawnBudgetPerUserTurn;
        }

        /**
         * 设置每用户轮次的创建预算。
         *
         * @param spawnBudgetPerUserTurn 每用户轮次的创建预算
         */
        public void setSpawnBudgetPerUserTurn(int spawnBudgetPerUserTurn) {
            this.spawnBudgetPerUserTurn = spawnBudgetPerUserTurn;
        }

        /**
         * 获取等待超时时间。
         *
         * @return 等待超时时间，单位毫秒
         */
        public long getWaitTimeoutMs() {
            return waitTimeoutMs;
        }

        /**
         * 设置等待超时时间。
         *
         * @param waitTimeoutMs 等待超时时间，单位毫秒
         */
        public void setWaitTimeoutMs(long waitTimeoutMs) {
            this.waitTimeoutMs = waitTimeoutMs;
        }

        /**
         * 获取执行器核心线程数。
         *
         * @return 执行器核心线程数
         */
        public int getExecutorCorePoolSize() {
            return executorCorePoolSize;
        }

        /**
         * 设置执行器核心线程数。
         *
         * @param executorCorePoolSize 执行器核心线程数
         */
        public void setExecutorCorePoolSize(int executorCorePoolSize) {
            this.executorCorePoolSize = executorCorePoolSize;
        }

        /**
         * 获取执行器最大线程数。
         *
         * @return 执行器最大线程数
         */
        public int getExecutorMaxPoolSize() {
            return executorMaxPoolSize;
        }

        /**
         * 设置执行器最大线程数。
         *
         * @param executorMaxPoolSize 执行器最大线程数
         */
        public void setExecutorMaxPoolSize(int executorMaxPoolSize) {
            this.executorMaxPoolSize = executorMaxPoolSize;
        }

        /**
         * 获取执行器队列容量。
         *
         * @return 执行器队列容量
         */
        public int getExecutorQueueCapacity() {
            return executorQueueCapacity;
        }

        /**
         * 设置执行器队列容量。
         *
         * @param executorQueueCapacity 执行器队列容量
         */
        public void setExecutorQueueCapacity(int executorQueueCapacity) {
            this.executorQueueCapacity = executorQueueCapacity;
        }

        /**
         * 获取创建子Agent时的系统提示。
         *
         * @return 创建子Agent时的系统提示
         */
        public String getSpawnSystemPromptHint() {
            return spawnSystemPromptHint;
        }

        /**
         * 设置创建子Agent时的系统提示。
         *
         * @param spawnSystemPromptHint 创建子Agent时的系统提示
         */
        public void setSpawnSystemPromptHint(String spawnSystemPromptHint) {
            this.spawnSystemPromptHint = spawnSystemPromptHint;
        }
    }

    /**
     * 运行时配置类。
     *
     * <p>配置Agent运行时行为参数，包括活跃运行清理、工具结果发布订阅和中断机制。
     */
    public static class Runtime {
        private boolean activeRunSweeperEnabled = true;
        private long activeRunSweeperIntervalMs = 2_000;
        private long activeRunStaleCleanupMs = 60_000;
        private boolean toolResultPubsubEnabled = true;
        private long toolResultPollIntervalMs = 500;
        private boolean interruptEnabled = true;

        /**
         * 判断活跃运行清理器是否启用。
         *
         * @return 活跃运行清理器是否启用
         */
        public boolean isActiveRunSweeperEnabled() {
            return activeRunSweeperEnabled;
        }

        /**
         * 设置活跃运行清理器是否启用。
         *
         * @param activeRunSweeperEnabled 活跃运行清理器是否启用
         */
        public void setActiveRunSweeperEnabled(boolean activeRunSweeperEnabled) {
            this.activeRunSweeperEnabled = activeRunSweeperEnabled;
        }

        /**
         * 获取活跃运行清理间隔时间。
         *
         * @return 活跃运行清理间隔时间，单位毫秒
         */
        public long getActiveRunSweeperIntervalMs() {
            return activeRunSweeperIntervalMs;
        }

        /**
         * 设置活跃运行清理间隔时间。
         *
         * @param activeRunSweeperIntervalMs 活跃运行清理间隔时间，单位毫秒
         */
        public void setActiveRunSweeperIntervalMs(long activeRunSweeperIntervalMs) {
            this.activeRunSweeperIntervalMs = activeRunSweeperIntervalMs;
        }

        /**
         * 获取活跃运行过期清理时间。
         *
         * @return 活跃运行过期清理时间，单位毫秒
         */
        public long getActiveRunStaleCleanupMs() {
            return activeRunStaleCleanupMs;
        }

        /**
         * 设置活跃运行过期清理时间。
         *
         * @param activeRunStaleCleanupMs 活跃运行过期清理时间，单位毫秒
         */
        public void setActiveRunStaleCleanupMs(long activeRunStaleCleanupMs) {
            this.activeRunStaleCleanupMs = activeRunStaleCleanupMs;
        }

        /**
         * 判断工具结果发布订阅是否启用。
         *
         * @return 工具结果发布订阅是否启用
         */
        public boolean isToolResultPubsubEnabled() {
            return toolResultPubsubEnabled;
        }

        /**
         * 设置工具结果发布订阅是否启用。
         *
         * @param toolResultPubsubEnabled 工具结果发布订阅是否启用
         */
        public void setToolResultPubsubEnabled(boolean toolResultPubsubEnabled) {
            this.toolResultPubsubEnabled = toolResultPubsubEnabled;
        }

        /**
         * 获取工具结果轮询间隔时间。
         *
         * @return 工具结果轮询间隔时间，单位毫秒
         */
        public long getToolResultPollIntervalMs() {
            return toolResultPollIntervalMs;
        }

        /**
         * 设置工具结果轮询间隔时间。
         *
         * @param toolResultPollIntervalMs 工具结果轮询间隔时间，单位毫秒
         */
        public void setToolResultPollIntervalMs(long toolResultPollIntervalMs) {
            this.toolResultPollIntervalMs = toolResultPollIntervalMs;
        }

        /**
         * 判断中断功能是否启用。
         *
         * @return 中断功能是否启用
         */
        public boolean isInterruptEnabled() {
            return interruptEnabled;
        }

        /**
         * 设置中断功能是否启用。
         *
         * @param interruptEnabled 中断功能是否启用
         */
        public void setInterruptEnabled(boolean interruptEnabled) {
            this.interruptEnabled = interruptEnabled;
        }
    }

    /**
     * 待办事项配置类。
     *
     * <p>配置Agent待办事项提醒的相关参数。
     */
    public static class Todo {
        private int reminderTurnInterval = 3;

        /**
         * 获取提醒轮次间隔。
         *
         * @return 提醒轮次间隔
         */
        public int getReminderTurnInterval() {
            return reminderTurnInterval;
        }

        /**
         * 设置提醒轮次间隔。
         *
         * @param reminderTurnInterval 提醒轮次间隔
         */
        public void setReminderTurnInterval(int reminderTurnInterval) {
            this.reminderTurnInterval = reminderTurnInterval;
        }
    }

    /**
     * 管理员配置类。
     *
     * <p>配置管理员权限相关的参数，包括是否启用管理员功能和认证令牌。
     */
    public static class Admin {
        private boolean enabled = false;
        private String token = "";

        /**
         * 判断管理员功能是否启用。
         *
         * @return 管理员功能是否启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置管理员功能是否启用。
         *
         * @param enabled 管理员功能是否启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取管理员认证令牌。
         *
         * @return 管理员认证令牌
         */
        public String getToken() {
            return token;
        }

        /**
         * 设置管理员认证令牌。
         *
         * @param token 管理员认证令牌
         */
        public void setToken(String token) {
            this.token = token;
        }
    }

}