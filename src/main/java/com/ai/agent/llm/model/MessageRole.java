package com.ai.agent.llm.model;

/**
 * 消息角色枚举。
 * <p>
 * 定义LLM消息的角色类型，包括系统、用户、助手和工具四种角色。
 * </p>
 */
public enum MessageRole {
    /**
     * 系统消息，用于设置LLM的行为指令。
     */
    SYSTEM,
    /**
     * 用户消息，表示用户的输入。
     */
    USER,
    /**
     * 助手消息，表示LLM的响应。
     */
    ASSISTANT,
    /**
     * 工具消息，表示工具调用的返回结果。
     */
    TOOL
}
