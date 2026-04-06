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
     * Simulate a real failure through the circuit breaker decorator.
     *
     * WHY not transitionToOpenState()?
     * transitionToOpenState() bypasses the CB entirely — it flips the state
     * flag directly without recording any calls in the sliding window.
     * This means:
     *   - failureRate stays at -1.0 (not evaluated)
     *   - numberOfFailedCalls stays at 0
     *   - The transition back to HALF_OPEN / CLOSED is not driven by real metrics
     *   - You're testing the state flag, not the circuit breaker behaviour
     *
     * This endpoint decorates a supplier that always throws with the named
     * circuit breaker, exactly as ResilientKafkaPublisher does in production.
     * The CB records a genuine failure in its sliding window, so:
     *   - After minimumNumberOfCalls failures >= failureRateThreshold%, CB → OPEN
     *   - After waitDurationInOpenState, CB → HALF_OPEN automatically
     *   - After permittedNumberOfCallsInHalfOpenState successes, CB → CLOSED
     *
     * Only active on cb-test profile — never exposed in production.
     *
     * @param name       circuit breaker name (paymentService, orderEventPublisher)
     * @param shouldFail true = record a failure, false = record a success
     */
    @PostMapping("/test/simulate-failure")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> simulateFailure(
            @RequestParam String name,
            @RequestParam(defaultValue = "true") boolean shouldFail) {

        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            String callOutcome;

            if (shouldFail) {
                // Execute a call through the CB that throws — CB records a FAILURE
                // The fallback catches the exception so we can return a 200 with
                // the CB state rather than propagating a 500.
                try {
                    cb.executeSupplier(() -> {
                        throw new RuntimeException("Simulated failure for CB test");
                    });
                    callOutcome = "success"; // shouldn't reach here
                } catch (Exception e) {
                    // CB recorded the failure — this is expected
                    callOutcome = "failure-recorded";
                    log.debug("[CB-TEST] {} recorded failure. State now: {}", name, cb.getState());
                }
            } else {
                // Execute a call through the CB that succeeds — CB records a SUCCESS
                // Used to drive HALF_OPEN → CLOSED transition
                cb.executeSupplier(() -> "ok");
                callOutcome = "success-recorded";
                log.debug("[CB-TEST] {} recorded success. State now: {}", name, cb.getState());
            }

            Map<String, Object> info = buildCbInfo(cb);
            info.put("name", name);
            info.put("callOutcome", callOutcome);
            return ResponseEntity.ok(info);

        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            // CB is OPEN — call was rejected before execution
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            Map<String, Object> info = buildCbInfo(cb);
            info.put("name", name);
            info.put("callOutcome", "rejected-circuit-open");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(info);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Simulate rate limiter exhaustion.
     * Fires N calls through the orderCreation rate limiter in quick succession.
     * Once the limit is exhausted, calls return 429.
     */
    @PostMapping("/test/simulate-rate-limit")
    @Profile("cb-test")
    public ResponseEntity<Map<String, Object>> simulateRateLimit(
            @RequestParam(defaultValue = "1") int calls) {

        int permitted = 0;
        int rejected = 0;

        RateLimiter rl = rateLimiterRegistry.rateLimiter("orderCreation");

        for (int i = 0; i < calls; i++) {
            boolean acquired = rl.acquirePermission();
            if (acquired) permitted++;
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