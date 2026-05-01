package com.ai.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_run_context")
public class AgentRunContextEntity {
    @TableId
    private String runId;
    private String effectiveAllowedTools;
    private String model;
    private Integer maxTurns;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getEffectiveAllowedTools() {
        return effectiveAllowedTools;
    }

    public void setEffectiveAllowedTools(String effectiveAllowedTools) {
        this.effectiveAllowedTools = effectiveAllowedTools;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(Integer maxTurns) {
        this.maxTurns = maxTurns;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
