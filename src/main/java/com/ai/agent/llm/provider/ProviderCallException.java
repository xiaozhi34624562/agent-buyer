package com.ai.agent.llm.provider;

/**
 * 提供者调用异常。
 * 表示LLM提供者调用过程中发生的错误，包含错误类型和HTTP状态码。
 */
public final class ProviderCallException extends RuntimeException {
    private final ProviderErrorType type;
    private final Integer statusCode;

    /**
     * 创建提供者调用异常。
     *
     * @param type    错误类型
     * @param message 错误消息
     */
    public ProviderCallException(ProviderErrorType type, String message) {
        this(type, message, null, null);
    }

    /**
     * 创建带原因的提供者调用异常。
     *
     * @param type    错误类型
     * @param message 错误消息
     * @param cause   原始异常
     */
    public ProviderCallException(ProviderErrorType type, String message, Throwable cause) {
        this(type, message, null, cause);
    }

    /**
     * 创建带状态码的提供者调用异常。
     *
     * @param type       错误类型
     * @param message    错误消息
     * @param statusCode HTTP状态码
     */
    public ProviderCallException(ProviderErrorType type, String message, Integer statusCode) {
        this(type, message, statusCode, null);
    }

    /**
     * 创建完整的提供者调用异常。
     *
     * @param type       错误类型
     * @param message    错误消息
     * @param statusCode HTTP状态码
     * @param cause      原始异常
     */
    public ProviderCallException(ProviderErrorType type, String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.statusCode = statusCode;
    }

    /**
     * 获取错误类型。
     *
     * @return 错误类型
     */
    public ProviderErrorType type() {
        return type;
    }

    /**
     * 获取HTTP状态码。
     *
     * @return 状态码，可能为null
     */
    public Integer statusCode() {
        return statusCode;
    }
}
