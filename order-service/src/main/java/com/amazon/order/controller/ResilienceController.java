package com.amazon.order.controller;

import com.amazon.order.config.ResilientKafkaPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resilience")
@RequiredArgsConstructor
@Slf4j
public class ResilienceController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final ResilientKafkaPublisher publisher;

    // ── Read endpoints (all profiles) ────────────────────────────────────────

    /**
     * Returns health of all registered circuit breakers.
     * Used by tests to assert state after driving failures.
     */
    @GetMapping("/circuit-breakers")
    public ResponseEntity<Map<String, Object>> getAllCircuitBreakers() {
        Map<String, Object> result = new HashMap<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb ->
                result.put(cb.getName(), buildCbInfo(cb)));
        return ResponseEntity.ok(result);
    }

    /**
     * Returns state + metrics for a specific circuit breaker.
     * 404 if the name is not registered.
     */
    @GetMapping("/circuit-breakers/{name}")
    public ResponseEntity<Map<String, Object>> getCircuitBreaker(@PathVariable String name) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            Map<String, Object> info = buildCbInfo(cb);
            info.put("name", cb.getName());
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── Ops endpoints (all profiles) ─────────────────────────────────────────

    /**
     * Reset a circuit breaker to CLOSED and wipe its metrics.
     * Use after a test to leave the CB in a clean state.
     *
     * WHY cb.reset() and not cb.transitionToClosedState()?
     * transitionToClosedState() keeps the existing call buffer — if it
     * already has 4 failures recorded, the very next call can trip it again.
     * reset() wipes the buffer entirely so the next test starts fresh.
     */
    @PostMapping("/circuit-breakers/{name}/reset")
    public ResponseEntity<Map<String, Object>> resetCircuitBreaker(@PathVariable String name) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            cb.reset();
            Map<String, Object> info = buildCbInfo(cb);
            info.put("name", name);
            info.put("message", "Circuit breaker reset — buffer wiped, state CLOSED");
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Test-only endpoints (cb-test profile only) ───────────────────────────

    /**
     * Simulate a real failure/success through the paymentService circuit breaker.
     *
     * WHY a dedicated path per CB, not ?name=cbName query param?
     * There are exactly two CBs in order-service (paymentService,
     * orderEventPublisher) and each wraps a specific call path in
     * ResilientKafkaPublisher. Making the CB name a query param would let
     * you pass any arbitrary string — a typo would silently create a new
     * CB instance with default config instead of hitting the configured one.
     * Path-per-CB is explicit, typo-safe, and matches the production code structure.
     *
     * WHY not transitionToOpenState()?
     * That bypasses the sliding window entirely — failureRate stays -1.0,
     * numberOfFailedCalls stays 0, HALF_OPEN transition is not timer-driven.
     * You'd be testing the state flag, not the CB state machine.
     * executeSupplier() goes through the full decorator chain, identical to
     * how ResilientKafkaPublisher.publishPaymentRequest() works in production.
     *
     * Only active on cb-test profile — never compiled into production.
     */
    @PostMapping("/test/payment-cb/failure")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> simulatePaymentCbFailure() {
        return recordCbCall("kafkaPaymentPublisher", false);
    }

    @PostMapping("/test/payment-cb/success")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> simulatePaymentCbSuccess() {
        return recordCbCall("kafkaPaymentPublisher", true);
    }

    @PostMapping("/test/order-event-cb/failure")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> simulateOrderEventCbFailure() {
        return recordCbCall("orderEventPublisher", false);
    }

    @PostMapping("/test/order-event-cb/success")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> simulateOrderEventCbSuccess() {
        return recordCbCall("orderEventPublisher", true);
    }

    /**
     * Exhaust the orderCreation rate limiter.
     * Acquires N permits in one call — any beyond the limit are rejected.
     * Only active on cb-test profile.
     */
    @PostMapping("/test/rate-limit/exhaust")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> exhaustRateLimit(
            @RequestParam(defaultValue = "1") int calls) {

        int permitted = 0;
        int rejected  = 0;
        RateLimiter rl = rateLimiterRegistry.rateLimiter("orderCreation");

        for (int i = 0; i < calls; i++) {
            if (rl.acquirePermission()) permitted++;
            else rejected++;
        }

        return ResponseEntity.ok(Map.of(
                "requestedCalls", calls,
                "permitted", permitted,
                "rejected", rejected,
                "availablePermissions", rl.getMetrics().getAvailablePermissions(),
                "waitingThreads", rl.getMetrics().getNumberOfWaitingThreads()
        ));
    }

    // ── Internal helper — shared by all simulate endpoints ───────────────────

    private ResponseEntity<Map<String, Object>> recordCbCall(String cbName, boolean shouldSucceed) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(cbName);
            String outcome;

            if (shouldSucceed) {
                cb.executeSupplier(() -> "ok");
                outcome = "success-recorded";
            } else {
                try {
                    cb.executeSupplier(() -> {
                        throw new RuntimeException("Simulated failure for CB test");
                    });
                    outcome = "success"; // unreachable
                } catch (Exception e) {
                    outcome = "failure-recorded";
                }
            }
            log.debug("[CB-TEST] {} {} — state now: {}", cbName, outcome, cb.getState());

            Map<String, Object> info = buildCbInfo(cb);
            info.put("name", cbName);
            info.put("callOutcome", outcome);
            return ResponseEntity.ok(info);

        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(cbName);
            Map<String, Object> info = buildCbInfo(cb);
            info.put("name", cbName);
            info.put("callOutcome", "rejected-circuit-open");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(info);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildCbInfo(CircuitBreaker cb) {
        Map<String, Object> info = new HashMap<>();
        info.put("state", cb.getState().name());
        info.put("failureRate", cb.getMetrics().getFailureRate());
        info.put("slowCallRate", cb.getMetrics().getSlowCallRate());
        info.put("numberOfBufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
        info.put("numberOfSuccessfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
        info.put("numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls());
        info.put("notPermittedCalls", cb.getMetrics().getNumberOfNotPermittedCalls());
        return info;
    }
}