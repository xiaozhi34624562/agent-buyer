package com.ai.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Agent上下文压缩实体类
 * <p>
 * 映射数据库表 agent_context_compaction，记录Agent运行过程中上下文压缩的详细信息，
 * 包括压缩策略、压缩前后Token数量变化等。
 * </p>
 */
@TableName("agent_context_compaction")
public class AgentContextCompactionEntity {
    @TableId
    private String compactionId;
    private String runId;
    private Integer turnNo;
    private String attemptId;
    private String strategy;
    private Integer beforeTokens;
    private Integer afterTokens;
    private String compactedMessageIds;
    private LocalDateTime createdAt;

    public String getCompactionId() {
        return compactionId;
    }

    public void setCompactionId(String compactionId) {
        this.compactionId = compactionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Integer getTurnNo() {
        return turnNo;
    }

    public void setTurnNo(Integer turnNo) {
        this.turnNo = turnNo;
    }

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Integer getBeforeTokens() {
        return beforeTokens;
    }

    public void setBeforeTokens(Integer beforeTokens) {
        this.beforeTokens = beforeTokens;
    }

    public Integer getAfterTokens() {
        return afterTokens;
    }

    public void setAfterTokens(Integer afterTokens) {
        this.afterTokens = afterTokens;
    }

    public String getCompactedMessageIds() {
        return compactedMessageIds;
    }

    public void setCompactedMessageIds(String compactedMessageIds) {
        this.compactedMessageIds = compactedMessageIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
