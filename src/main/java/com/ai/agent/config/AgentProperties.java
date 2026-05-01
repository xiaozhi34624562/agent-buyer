package com.ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private String redisKeyPrefix = "agent";
    private int maxParallel = 6;
    private int maxScan = 256;
    private long leaseMs = 90_000;
    private Duration confirmationTtl = Duration.ofMinutes(10);
    private Reaper reaper = new Reaper();
    private AgentLoop agentLoop = new AgentLoop();
    private Executor executor = new Executor();
    private Llm llm = new Llm();
    private RateLimit rateLimit = new RateLimit();

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
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

    public static class AgentLoop {
        private int maxTurns = 10;
        private long toolResultTimeoutMs = 90_000;
        private long runWallclockTimeoutMs = 300_000;
        private int hardTokenCap = 30_000;

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
}
