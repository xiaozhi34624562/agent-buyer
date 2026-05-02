package com.ai.agent.config;

import com.ai.agent.loop.AgentLoop;
import com.ai.agent.subagent.tool.AgentTool;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public List<String> getDefaultAllowedTools() {
        return defaultAllowedTools;
    }

    public void setDefaultAllowedTools(List<String> defaultAllowedTools) {
        this.defaultAllowedTools = defaultAllowedTools == null ? List.of() : List.copyOf(defaultAllowedTools);
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public int getMaxScan() {
        return maxScan;
    }

    public void setMaxScan(int maxScan) {
        this.maxScan = maxScan;
    }

    public long getLeaseMs() {
        return leaseMs;
    }

    public void setLeaseMs(long leaseMs) {
        this.leaseMs = leaseMs;
    }

    public Duration getConfirmationTtl() {
        return confirmationTtl;
    }

    public void setConfirmationTtl(Duration confirmationTtl) {
        this.confirmationTtl = confirmationTtl;
    }

    public Reaper getReaper() {
        return reaper;
    }

    public void setReaper(Reaper reaper) {
        this.reaper = reaper;
    }

    public AgentLoop getAgentLoop() {
        return agentLoop;
    }

    public void setAgentLoop(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public RequestPolicy getRequestPolicy() {
        return requestPolicy;
    }

    public void setRequestPolicy(RequestPolicy requestPolicy) {
        this.requestPolicy = requestPolicy;
    }

    public Context getContext() {
        return context;
    }

    public void setContext(Context context) {
        this.context = context == null ? new Context() : context;
    }

    public Skills getSkills() {
        return skills;
    }

    public void setSkills(Skills skills) {
        this.skills = skills == null ? new Skills() : skills;
    }

    public SubAgent getSubAgent() {
        return subAgent;
    }

    public void setSubAgent(SubAgent subAgent) {
        this.subAgent = subAgent == null ? new SubAgent() : subAgent;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public void setRuntime(Runtime runtime) {
        this.runtime = runtime == null ? new Runtime() : runtime;
    }

    public Todo getTodo() {
        return todo;
    }

    public void setTodo(Todo todo) {
        this.todo = todo == null ? new Todo() : todo;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin == null ? new Admin() : admin;
    }

    public static class AgentLoop {
        private int maxTurns = 10;
        private long toolResultTimeoutMs = 90_000;
        private long runWallclockTimeoutMs = 300_000;
        private int hardTokenCap = 30_000;
        private int llmCallBudgetPerUserTurn = 30;
        private int subAgentLlmCallBudgetPerUserTurn = 30;
        private int runWideLlmCallBudget = 80;

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public long getToolResultTimeoutMs() {
            return toolResultTimeoutMs;
        }

        public void setToolResultTimeoutMs(long toolResultTimeoutMs) {
            this.toolResultTimeoutMs = toolResultTimeoutMs;
        }

        public long getRunWallclockTimeoutMs() {
            return runWallclockTimeoutMs;
        }

        public void setRunWallclockTimeoutMs(long runWallclockTimeoutMs) {
            this.runWallclockTimeoutMs = runWallclockTimeoutMs;
        }

        public int getHardTokenCap() {
            return hardTokenCap;
        }

        public void setHardTokenCap(int hardTokenCap) {
            this.hardTokenCap = hardTokenCap;
        }

        public int getLlmCallBudgetPerUserTurn() {
            return llmCallBudgetPerUserTurn;
        }

        public void setLlmCallBudgetPerUserTurn(int llmCallBudgetPerUserTurn) {
            this.llmCallBudgetPerUserTurn = llmCallBudgetPerUserTurn;
        }

        public int getSubAgentLlmCallBudgetPerUserTurn() {
            return subAgentLlmCallBudgetPerUserTurn;
        }

        public void setSubAgentLlmCallBudgetPerUserTurn(int subAgentLlmCallBudgetPerUserTurn) {
            this.subAgentLlmCallBudgetPerUserTurn = subAgentLlmCallBudgetPerUserTurn;
        }

        public int getRunWideLlmCallBudget() {
            return runWideLlmCallBudget;
        }

        public void setRunWideLlmCallBudget(int runWideLlmCallBudget) {
            this.runWideLlmCallBudget = runWideLlmCallBudget;
        }
    }

    public static class Reaper {
        private boolean enabled = true;
        private long intervalMs = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    public static class Executor {
        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 256;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Llm {
        private String provider = "deepseek";
        private DeepSeek deepseek = new DeepSeek();
        private Qwen qwen = new Qwen();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public DeepSeek getDeepseek() {
            return deepseek;
        }

        public void setDeepseek(DeepSeek deepseek) {
            this.deepseek = deepseek;
        }

        public Qwen getQwen() {
            return qwen;
        }

        public void setQwen(Qwen qwen) {
            this.qwen = qwen;
        }
    }

    public static class DeepSeek {
        private String baseUrl = "https://api.deepseek.com/v1";
        private String apiKey = "";
        private String defaultModel = "deepseek-reasoner";
        private Duration requestTimeout = Duration.ofSeconds(60);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class Qwen {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String defaultModel = "qwen-plus";
        private Duration requestTimeout = Duration.ofSeconds(60);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public Duration getRequestTimeout() {
            return requestTimeout;
        }

        public void setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
        }
    }

    public static class RateLimit {
        private int runsPerUserPerMinute = 5;
        private int tokensPerUserPerDay = 100_000;

        public int getRunsPerUserPerMinute() {
            return runsPerUserPerMinute;
        }

        public void setRunsPerUserPerMinute(int runsPerUserPerMinute) {
            this.runsPerUserPerMinute = runsPerUserPerMinute;
        }

        public int getTokensPerUserPerDay() {
            return tokensPerUserPerDay;
        }

        public void setTokensPerUserPerDay(int tokensPerUserPerDay) {
            this.tokensPerUserPerDay = tokensPerUserPerDay;
        }
    }

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

        public int getMaxMessages() {
            return maxMessages;
        }

        public void setMaxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        public int getMaxContentChars() {
            return maxContentChars;
        }

        public void setMaxContentChars(int maxContentChars) {
            this.maxContentChars = maxContentChars;
        }

        public int getMaxTotalContentChars() {
            return maxTotalContentChars;
        }

        public void setMaxTotalContentChars(int maxTotalContentChars) {
            this.maxTotalContentChars = maxTotalContentChars;
        }

        public double getMinTemperature() {
            return minTemperature;
        }

        public void setMinTemperature(double minTemperature) {
            this.minTemperature = minTemperature;
        }

        public double getMaxTemperature() {
            return maxTemperature;
        }

        public void setMaxTemperature(double maxTemperature) {
            this.maxTemperature = maxTemperature;
        }

        public int getMinMaxTokens() {
            return minMaxTokens;
        }

        public void setMinMaxTokens(int minMaxTokens) {
            this.minMaxTokens = minMaxTokens;
        }

        public int getMaxMaxTokens() {
            return maxMaxTokens;
        }

        public void setMaxMaxTokens(int maxMaxTokens) {
            this.maxMaxTokens = maxMaxTokens;
        }

        public int getMinMaxTurns() {
            return minMaxTurns;
        }

        public void setMinMaxTurns(int minMaxTurns) {
            this.minMaxTurns = minMaxTurns;
        }

        public int getMaxMaxTurns() {
            return maxMaxTurns;
        }

        public void setMaxMaxTurns(int maxMaxTurns) {
            this.maxMaxTurns = maxMaxTurns;
        }

        public List<String> getAllowedModels() {
            return allowedModels;
        }

        public void setAllowedModels(List<String> allowedModels) {
            this.allowedModels = allowedModels == null ? List.of() : List.copyOf(allowedModels);
        }
    }

    public static class Context {
        private int largeResultThresholdTokens = 2_000;
        private int largeResultHeadTokens = 200;
        private int largeResultTailTokens = 200;
        private int microCompactThresholdTokens = 50_000;
        private int summaryCompactThresholdTokens = 30_000;
        private int recentMessageBudgetTokens = 2_000;
        private int summaryMaxTokens = 1_200;

        public int getLargeResultThresholdTokens() {
            return largeResultThresholdTokens;
        }

        public void setLargeResultThresholdTokens(int largeResultThresholdTokens) {
            this.largeResultThresholdTokens = largeResultThresholdTokens;
        }

        public int getLargeResultHeadTokens() {
            return largeResultHeadTokens;
        }

        public void setLargeResultHeadTokens(int largeResultHeadTokens) {
            this.largeResultHeadTokens = largeResultHeadTokens;
        }

        public int getLargeResultTailTokens() {
            return largeResultTailTokens;
        }

        public void setLargeResultTailTokens(int largeResultTailTokens) {
            this.largeResultTailTokens = largeResultTailTokens;
        }

        public int getMicroCompactThresholdTokens() {
            return microCompactThresholdTokens;
        }

        public void setMicroCompactThresholdTokens(int microCompactThresholdTokens) {
            this.microCompactThresholdTokens = microCompactThresholdTokens;
        }

        public int getSummaryCompactThresholdTokens() {
            return summaryCompactThresholdTokens;
        }

        public void setSummaryCompactThresholdTokens(int summaryCompactThresholdTokens) {
            this.summaryCompactThresholdTokens = summaryCompactThresholdTokens;
        }

        public int getRecentMessageBudgetTokens() {
            return recentMessageBudgetTokens;
        }

        public void setRecentMessageBudgetTokens(int recentMessageBudgetTokens) {
            this.recentMessageBudgetTokens = recentMessageBudgetTokens;
        }

        public int getSummaryMaxTokens() {
            return summaryMaxTokens;
        }

        public void setSummaryMaxTokens(int summaryMaxTokens) {
            this.summaryMaxTokens = summaryMaxTokens;
        }
    }

    public static class Skills {
        private String rootPath = "classpath:skills";
        private List<String> enabledSkillNames = List.of(
                "purchase-guide",
                "return-exchange-guide",
                "order-issue-support"
        );
        private int maxPerMessage = 3;
        private int maxTokenPerMessage = 8_000;

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath;
        }

        public List<String> getEnabledSkillNames() {
            return enabledSkillNames;
        }

        public void setEnabledSkillNames(List<String> enabledSkillNames) {
            this.enabledSkillNames = enabledSkillNames == null ? List.of() : List.copyOf(enabledSkillNames);
        }

        public int getMaxPerMessage() {
            return maxPerMessage;
        }

        public void setMaxPerMessage(int maxPerMessage) {
            this.maxPerMessage = maxPerMessage;
        }

        public int getMaxTokenPerMessage() {
            return maxTokenPerMessage;
        }

        public void setMaxTokenPerMessage(int maxTokenPerMessage) {
            this.maxTokenPerMessage = maxTokenPerMessage;
        }
    }

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

        public int getMaxSpawnPerRun() {
            return maxSpawnPerRun;
        }

        public void setMaxSpawnPerRun(int maxSpawnPerRun) {
            this.maxSpawnPerRun = maxSpawnPerRun;
        }

        public int getMaxConcurrentPerRun() {
            return maxConcurrentPerRun;
        }

        public void setMaxConcurrentPerRun(int maxConcurrentPerRun) {
            this.maxConcurrentPerRun = maxConcurrentPerRun;
        }

        public int getSpawnBudgetPerUserTurn() {
            return spawnBudgetPerUserTurn;
        }

        public void setSpawnBudgetPerUserTurn(int spawnBudgetPerUserTurn) {
            this.spawnBudgetPerUserTurn = spawnBudgetPerUserTurn;
        }

        public long getWaitTimeoutMs() {
            return waitTimeoutMs;
        }

        public void setWaitTimeoutMs(long waitTimeoutMs) {
            this.waitTimeoutMs = waitTimeoutMs;
        }

        public int getExecutorCorePoolSize() {
            return executorCorePoolSize;
        }

        public void setExecutorCorePoolSize(int executorCorePoolSize) {
            this.executorCorePoolSize = executorCorePoolSize;
        }

        public int getExecutorMaxPoolSize() {
            return executorMaxPoolSize;
        }

        public void setExecutorMaxPoolSize(int executorMaxPoolSize) {
            this.executorMaxPoolSize = executorMaxPoolSize;
        }

        public int getExecutorQueueCapacity() {
            return executorQueueCapacity;
        }

        public void setExecutorQueueCapacity(int executorQueueCapacity) {
            this.executorQueueCapacity = executorQueueCapacity;
        }

        public String getSpawnSystemPromptHint() {
            return spawnSystemPromptHint;
        }

        public void setSpawnSystemPromptHint(String spawnSystemPromptHint) {
            this.spawnSystemPromptHint = spawnSystemPromptHint;
        }
    }

    public static class Runtime {
        private boolean activeRunSweeperEnabled = true;
        private long activeRunSweeperIntervalMs = 2_000;
        private long activeRunStaleCleanupMs = 60_000;
        private boolean toolResultPubsubEnabled = true;
        private long toolResultPollIntervalMs = 500;
        private boolean interruptEnabled = true;

        public boolean isActiveRunSweeperEnabled() {
            return activeRunSweeperEnabled;
        }

        public void setActiveRunSweeperEnabled(boolean activeRunSweeperEnabled) {
            this.activeRunSweeperEnabled = activeRunSweeperEnabled;
        }

        public long getActiveRunSweeperIntervalMs() {
            return activeRunSweeperIntervalMs;
        }

        public void setActiveRunSweeperIntervalMs(long activeRunSweeperIntervalMs) {
            this.activeRunSweeperIntervalMs = activeRunSweeperIntervalMs;
        }

        public long getActiveRunStaleCleanupMs() {
            return activeRunStaleCleanupMs;
        }

        public void setActiveRunStaleCleanupMs(long activeRunStaleCleanupMs) {
            this.activeRunStaleCleanupMs = activeRunStaleCleanupMs;
        }

        public boolean isToolResultPubsubEnabled() {
            return toolResultPubsubEnabled;
        }

        public void setToolResultPubsubEnabled(boolean toolResultPubsubEnabled) {
            this.toolResultPubsubEnabled = toolResultPubsubEnabled;
        }

        public long getToolResultPollIntervalMs() {
            return toolResultPollIntervalMs;
        }

        public void setToolResultPollIntervalMs(long toolResultPollIntervalMs) {
            this.toolResultPollIntervalMs = toolResultPollIntervalMs;
        }

        public boolean isInterruptEnabled() {
            return interruptEnabled;
        }

        public void setInterruptEnabled(boolean interruptEnabled) {
            this.interruptEnabled = interruptEnabled;
        }
    }

    public static class Todo {
        private int reminderTurnInterval = 3;

        public int getReminderTurnInterval() {
            return reminderTurnInterval;
        }

        public void setReminderTurnInterval(int reminderTurnInterval) {
            this.reminderTurnInterval = reminderTurnInterval;
        }
    }

    public static class Admin {
        private boolean enabled = false;
        private String token = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

}
