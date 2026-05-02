package com.ai.agent.tool.runtime.redis;

import com.ai.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

/**
 * Redis键名生成器。
 * 为运行相关的各种Redis数据结构生成标准化的键名。
 */
@Component
public final class RedisKeys {
    private final AgentProperties properties;

    /**
     * 构造Redis键名生成器。
     *
     * @param properties 配置属性
     */
    public RedisKeys(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取元数据键名。
     *
     * @param runId 运行ID
     * @return 元数据键名
     */
    public String meta(String runId) {
        return base(runId) + ":meta";
    }

    /**
     * 获取队列键名。
     *
     * @param runId 运行ID
     * @return 队列键名
     */
    public String queue(String runId) {
        return base(runId) + ":queue";
    }

    /**
     * 获取工具状态哈希键名。
     *
     * @param runId 运行ID
     * @return 工具状态键名
     */
    public String tools(String runId) {
        return base(runId) + ":tools";
    }

    /**
     * 获取工具使用ID集合键名。
     *
     * @param runId 运行ID
     * @return 工具使用ID键名
     */
    public String toolUseIds(String runId) {
        return base(runId) + ":tool-use-ids";
    }

    /**
     * 获取租约哈希键名。
     *
     * @param runId 运行ID
     * @return 租约键名
     */
    public String leases(String runId) {
        return base(runId) + ":leases";
    }

    /**
     * 获取续运行锁键名。
     *
     * @param runId 运行ID
     * @return 续运行锁键名
     */
    public String continuationLock(String runId) {
        return base(runId) + ":continuation-lock";
    }

    /**
     * 获取活跃运行集合键名。
     *
     * @return 活跃运行键名
     */
    public String activeRuns() {
        return properties.getRedisKeyPrefix() + ":active-runs";
    }

    /**
     * 获取控制键名。
     *
     * @param runId 运行ID
     * @return 控制键名
     */
    public String control(String runId) {
        return base(runId) + ":control";
    }

    /**
     * 获取结果发布频道键名。
     *
     * @param runId 运行ID
     * @return 结果频道键名
     */
    public String resultChannel(String runId) {
        return base(runId) + ":pubsub:result";
    }

    /**
     * 获取结果发布频道模式键名。
     *
     * @return 结果频道模式键名
     */
    public String resultChannelPattern() {
        return properties.getRedisKeyPrefix() + ":{run:*}:pubsub:result";
    }

    /**
     * 获取LLM调用预算键名。
     *
     * @param runId 运行ID
     * @return 预算键名
     */
    public String llmCallBudget(String runId) {
        return base(runId) + ":llm-call-budget";
    }

    /**
     * 获取子运行集合键名。
     *
     * @param runId 运行ID
     * @return 子运行键名
     */
    public String children(String runId) {
        return base(runId) + ":children";
    }

    /**
     * 获取ToDo列表键名。
     *
     * @param runId 运行ID
     * @return ToDo键名
     */
    public String todos(String runId) {
        return base(runId) + ":todos";
    }

    /**
     * 获取ToDo提醒键名。
     *
     * @param runId 运行ID
     * @return ToDo提醒键名
     */
    public String todoReminder(String runId) {
        return base(runId) + ":todo-reminder";
    }

    /**
     * 生成基础键名。
     *
     * @param runId 运行ID
     * @return 基础键名
     */
    private String base(String runId) {
        return properties.getRedisKeyPrefix() + ":{run:" + runId + "}";
    }
}
