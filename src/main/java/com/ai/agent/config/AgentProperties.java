package com.ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

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
    }

}
