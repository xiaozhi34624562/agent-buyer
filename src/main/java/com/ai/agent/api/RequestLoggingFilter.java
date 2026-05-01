package com.ai.agent.api;

import com.ai.agent.util.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final Pattern RUN_PATH = Pattern.compile("/api/agent/runs/([^/]+)");

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

    private String headerOrNewRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        return requestId == null || requestId.isBlank() ? Ids.newId("req") : requestId;
    }

    private String extractRunId(String path) {
        if (path == null) {
            return null;
        }
        Matcher matcher = RUN_PATH.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
