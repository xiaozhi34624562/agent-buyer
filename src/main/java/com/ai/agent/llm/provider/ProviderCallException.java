package com.ai.agent.llm.provider;

public final class ProviderCallException extends RuntimeException {
    private final ProviderErrorType type;
    private final Integer statusCode;

    public ProviderCallException(ProviderErrorType type, String message) {
        this(type, message, null, null);
    }

    public ProviderCallException(ProviderErrorType type, String message, Throwable cause) {
        this(type, message, null, cause);
    }

    public ProviderCallException(ProviderErrorType type, String message, Integer statusCode) {
        this(type, message, statusCode, null);
    }

    public ProviderCallException(ProviderErrorType type, String message, Integer statusCode, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.statusCode = statusCode;
    }

    public ProviderErrorType type() {
        return type;
    }

    public Integer statusCode() {
        return statusCode;
    }
}
