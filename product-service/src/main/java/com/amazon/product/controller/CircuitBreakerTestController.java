package com.amazon.product.controller;

import com.amazon.product.filter.CircuitBreakerTestState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Circuit Breaker Test Controller
 *
 * IMPORTANT: Add this to ALL services (user, product, order, payment)
 * to enable automated circuit breaker testing without manual service restarts.
 *
 * How to use in tests:
 *   1. POST http://localhost:8081/api/v1/test/circuit-breaker/fail
 *      → user-service starts returning 503 for all requests
 *   2. Send requests through gateway → circuit breaker trips
 *   3. POST http://localhost:8081/api/v1/test/circuit-breaker/recover
 *      → user-service resumes normal operation
 *   4. Send requests through gateway → circuit breaker recovers
 *
 * Endpoints per service:
 *   - user-service:    http://localhost:8081/api/v1/test/circuit-breaker/{fail|recover|status}
 *   - product-service: http://localhost:8082/api/v1/test/circuit-breaker/{fail|recover|status}
 *   - order-service:   http://localhost:8083/api/v1/test/circuit-breaker/{fail|recover|status}
 *   - payment-service: http://localhost:8084/api/v1/test/circuit-breaker/{fail|recover|status}
 */
@RestController
@RequestMapping("/api/v1/test/circuit-breaker")
@Slf4j
public class CircuitBreakerTestController {

    @PostMapping("/fail")
    public ResponseEntity<Map<String, Object>> startFailureSimulation() {
        CircuitBreakerTestState.setSimulatingFailure(true);
        log.warn("🔴 CB TEST MODE: SIMULATING FAILURE - returning 503 for all requests");

        return ResponseEntity.ok(Map.of(
                "status", "failure-mode-active",
                "message", "Service will now return 503 for all requests (except /test and /actuator)",
                "isSimulatingFailure", true
        ));
    }

    @PostMapping("/recover")
    public ResponseEntity<Map<String, Object>> stopFailureSimulation() {
        CircuitBreakerTestState.setSimulatingFailure(false);
        log.info("🟢 CB TEST MODE: RECOVERED - service operating normally");

        return ResponseEntity.ok(Map.of(
                "status", "normal-operation",
                "message", "Service recovered - operating normally",
                "isSimulatingFailure", false
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        boolean failing = CircuitBreakerTestState.isSimulatingFailure();

        return ResponseEntity.ok(Map.of(
                "isSimulatingFailure", failing,
                "status", failing ? "failure-mode-active" : "normal-operation",
                "message", failing
                        ? "Service is simulating failures (returning 503)"
                        : "Service is operating normally"
        ));
    }
}