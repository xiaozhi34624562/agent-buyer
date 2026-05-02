package com.ai.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Agent LLM调用尝试实体类
 * <p>
 * 映射数据库表 agent_llm_attempt，记录Agent每次调用LLM的详细信息，
 * 包括模型提供商、模型名称、Token消耗、调用状态等。
 * </p>
 */
@TableName("agent_llm_attempt")
public class AgentLlmAttemptEntity {
    @TableId
    private String attemptId;
    private String runId;
    private Integer turnNo;
    private String provider;
    private String model;
    private String status;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String errorJson;
    private String rawDiagnosticJson;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public String getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(String attemptId) {
        this.attemptId = attemptId;
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getErrorJson() {
        return errorJson;
    }

    public void setErrorJson(String errorJson) {
        this.errorJson = errorJson;
    }

    public String getRawDiagnosticJson() {
        return rawDiagnosticJson;
    }

    public void setRawDiagnosticJson(String rawDiagnosticJson) {
        this.rawDiagnosticJson = rawDiagnosticJson;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
