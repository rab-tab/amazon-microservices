package com.amazon.user.filter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Circuit Breaker Test Filter
 *
 * Intercepts ALL incoming requests and returns 503 when in failure simulation mode.
 * This simulates the service being down without actually stopping it.
 *
 * How it works:
 *   1. Test calls POST /api/v1/test/circuit-breaker/fail
 *   2. This filter starts returning 503 for ALL requests (except test control endpoints)
 *   3. Gateway's circuit breaker trips after N failures
 *   4. Test calls POST /api/v1/test/circuit-breaker/recover
 *   5. This filter stops intercepting - normal operation resumes
 *
 * Excluded paths (always allowed):
 *   - /api/v1/test/circuit-breaker/** (test control endpoints)
 *   - /actuator/** (monitoring endpoints)
 *
 * Thread-safe: uses volatile boolean in CircuitBreakerTestState
 */
@Component
@Slf4j
public class CircuitBreakerTestFilter implements Filter {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        // Always allow test control endpoints and actuator
        if (path.startsWith("/api/v1/test/circuit-breaker") ||
                path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        // Check if we should simulate failure
        if (CircuitBreakerTestState.isSimulatingFailure()) {
            log.debug("🔴 Simulating service failure for request: {} {}",
                    httpRequest.getMethod(), path);

            httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            httpResponse.setContentType("application/json");

            Map<String, Object> errorResponse = Map.of(
                    "timestamp", System.currentTimeMillis(),
                    "status", 503,
                    "error", "Service Unavailable",
                    "message", "Service is simulating failure for circuit breaker testing",
                    "path", path,
                    "testMode", true
            );

            httpResponse.getWriter().write(objectMapper.writeValueAsString(errorResponse));
            return;
        }

        // Normal operation - continue the filter chain
        chain.doFilter(request, response);
    }
}
