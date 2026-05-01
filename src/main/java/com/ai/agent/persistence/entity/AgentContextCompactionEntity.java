package com.ai.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

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
