package com.ai.agent.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {
    @Test
    void populatesRequestMdcAndResponseHeaderThenClearsAfterRequest() throws ServletException, IOException {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/runs/run_123/messages");
        request.addHeader("X-User-Id", "demo-user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> requestId = new AtomicReference<>();
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> runId = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            requestId.set(MDC.get("requestId"));
            userId.set(MDC.get("userId"));
            runId.set(MDC.get("runId"));
            method.set(MDC.get("method"));
            path.set(MDC.get("path"));
        };

        filter.doFilter(request, response, chain);

        assertThat(requestId.get()).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(requestId.get());
        assertThat(userId.get()).isEqualTo("demo-user");
        assertThat(runId.get()).isEqualTo("run_123");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/api/agent/runs/run_123/messages");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void preservesIncomingRequestId() throws ServletException, IOException {
        RequestLoggingFilter filter = new RequestLoggingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader("X-Request-Id", "req-client-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> requestId = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> requestId.set(MDC.get("requestId")));

        assertThat(requestId.get()).isEqualTo("req-client-1");
        assertThat(response.getHeader("X-Request-Id")).isEqualTo("req-client-1");
    }
}
