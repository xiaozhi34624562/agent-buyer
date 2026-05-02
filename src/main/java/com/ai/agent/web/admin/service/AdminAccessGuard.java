package com.ai.agent.web.admin.service;

import com.ai.agent.config.AgentProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 管理后台访问守卫。
 *
 * <p>负责验证管理后台访问权限，支持 Token 验证和在特定环境（local/demo）下免 Token 访问。
 * 使用 SHA-256 比较避免时序攻击。
 *
 * @author AI Agent
 */
@Component
public class AdminAccessGuard {

    private static final Set<String> TOKENLESS_ALLOWED_PROFILES = Set.of("local", "demo");

    private final AgentProperties properties;
    private final Environment environment;

    /**
     * 构造访问守卫。
     *
     * @param properties Agent 配置属性
     * @param environment Spring 环境配置
     */
    public AdminAccessGuard(AgentProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * 检查访问权限。
     *
     * @param providedToken 提供的访问 Token
     * @return 是否有访问权限
     * @throws AdminAccessDeniedException 访问被拒绝时抛出
     */
    public boolean checkAccess(String providedToken) {
        if (!properties.getAdmin().isEnabled()) {
            throw new AdminAccessDeniedException("admin console disabled");
        }

        String[] activeProfiles = environment.getActiveProfiles();
        boolean isTokenlessAllowed = Arrays.stream(activeProfiles)
                .anyMatch(TOKENLESS_ALLOWED_PROFILES::contains);

        String configuredToken = properties.getAdmin().getToken();

        if (configuredToken == null || configuredToken.isBlank()) {
            if (isTokenlessAllowed) {
                return true;
            }
            throw new AdminAccessDeniedException("admin token required for non-local/demo profile");
        }

        if (providedToken == null || providedToken.isBlank()) {
            if (isTokenlessAllowed) {
                return true;
            }
            throw new AdminAccessDeniedException("admin token required");
        }

        if (!tokenMatches(configuredToken, providedToken)) {
            throw new AdminAccessDeniedException("invalid admin token");
        }

        return true;
    }

    /**
     * 使用 SHA-256 安全比较 Token，防止时序攻击。
     *
     * @param configuredToken 配置的 Token
     * @param providedToken   提供的 Token
     * @return 是否匹配
     */
    private boolean tokenMatches(String configuredToken, String providedToken) {
        return MessageDigest.isEqual(sha256(configuredToken), sha256(providedToken));
    }

    /**
     * 计算字符串的 SHA-256 哈希值。
     *
     * @param value 待哈希字符串
     * @return SHA-256 哈希字节数组
     */
    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 管理后台访问被拒绝异常。
     */
    public static class AdminAccessDeniedException extends RuntimeException {

        /**
         * 构造访问拒绝异常。
         *
         * @param message 拒绝原因
         */
        public AdminAccessDeniedException(String message) {
            super(message);
        }
    }
}
