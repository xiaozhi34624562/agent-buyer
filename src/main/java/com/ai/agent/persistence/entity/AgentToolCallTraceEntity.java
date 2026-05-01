package com.ai.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_tool_call_trace")
public class AgentToolCallTraceEntity {
    @TableId
    private String toolCallId;
    private String runId;
    private String messageId;
    private Long seq;
    private String toolUseId;
    private String rawToolName;
    private String toolName;
    private String argsJson;
    @TableField("is_concurrent")
    private Boolean concurrent;
    private Boolean precheckFailed;
    private String precheckErrorJson;
    private LocalDateTime createdAt;

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public Long getSeq() {
        return seq;
    }

    public void setSeq(Long seq) {
        this.seq = seq;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getRawToolName() {
        return rawToolName;
    }

    public void setRawToolName(String rawToolName) {
        this.rawToolName = rawToolName;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public void setArgsJson(String argsJson) {
        this.argsJson = argsJson;
    }

    public Boolean getConcurrent() {
        return concurrent;
    }

    public void setConcurrent(Boolean concurrent) {
        this.concurrent = concurrent;
    }

    public Boolean getPrecheckFailed() {
        return precheckFailed;
    }

    public void setPrecheckFailed(Boolean precheckFailed) {
        this.precheckFailed = precheckFailed;
    }

    public String getPrecheckErrorJson() {
        return precheckErrorJson;
    }

    public void setPrecheckErrorJson(String precheckErrorJson) {
        this.precheckErrorJson = precheckErrorJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
