package com.ai.agent.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Agent工具进度实体类
 * <p>
 * 映射数据库表 agent_tool_progress，记录Agent工具执行过程中的进度信息，
 * 包括执行阶段、进度消息、完成百分比等。
 * </p>
 */
@TableName("agent_tool_progress")
public class AgentToolProgressEntity {
    @TableId
    private String progressId;
    private String runId;
    private String toolCallId;
    private String stage;
    private String message;
    private Integer percent;
    private LocalDateTime createdAt;

    public String getProgressId() {
        return progressId;
    }

    public void setProgressId(String progressId) {
        this.progressId = progressId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getPercent() {
        return percent;
    }

    public void setPercent(Integer percent) {
        this.percent = percent;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
