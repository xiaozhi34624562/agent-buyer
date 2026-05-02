package com.ai.agent.web.filter;

import com.ai.agent.util.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP 请求日志过滤器。
 *
 * <p>为每个 HTTP 请求生成唯一请求 ID 并记录请求开始和完成日志，
 * 将请求相关信息（请求 ID、用户 ID、运行 ID 等）放入 MDC 便于后续日志追踪。
 *
 * @author AI Agent
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final Pattern RUN_PATH = Pattern.compile("/api/agent/runs/([^/]+)");

    /**
     * 执行请求过滤逻辑。
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = headerOrNewRequestId(request);
        long startedAtNanos = System.nanoTime();
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            put("requestId", requestId);
            put("userId", request.getHeader(USER_ID_HEADER));
            put("runId", extractRunId(request.getRequestURI()));
            put("method", request.getMethod());
            put("path", request.getRequestURI());
            log.info("http request started");
            filterChain.doFilter(request, response);
            log.info(
                    "http request completed status={} durationMs={}",
                    response.getStatus(),
                    elapsedMillis(startedAtNanos)
            );
        } catch (IOException | ServletException | RuntimeException e) {
            log.warn(
                    "http request failed status={} durationMs={} error={}",
                    response.getStatus(),
                    elapsedMillis(startedAtNanos),
                    e.getMessage()
            );
            throw e;
        } finally {
            MDC.clear();
        }
    }

    /**
     * 从请求头获取请求 ID，若不存在则生成新的 ID。
     *
     * @param request HTTP 请求
     * @return 请求 ID
     */
    private String headerOrNewRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        return requestId == null || requestId.isBlank() ? Ids.newId("req") : requestId;
    }

    /**
     * 从请求路径中提取运行 ID。
     *
     * @param path 请求路径
     * @return 运行 ID，若路径不匹配则返回 null
     */
    private String extractRunId(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = RUN_PATH.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 将非空值放入 MDC。
     *
     * @param key   MDC 键
     * @param value MDC 值
     */
    private void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    /**
     * 计算请求耗时（毫秒）。
     *
     * @param startedAtNanos 开始时间（纳秒）
     * @return 耗时毫秒数
     */
    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
