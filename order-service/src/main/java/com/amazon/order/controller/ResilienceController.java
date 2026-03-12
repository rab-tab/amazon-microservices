package com.amazon.order.controller;

import com.amazon.order.config.ResilientKafkaPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resilience")
@RequiredArgsConstructor
public class ResilienceController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ResilientKafkaPublisher publisher;

    /** Returns health of all registered circuit breakers */
    @GetMapping("/circuit-breakers")
    public ResponseEntity<Map<String, Object>> getAllCircuitBreakers() {
        Map<String, Object> result = new HashMap<>();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            Map<String, Object> cbInfo = new HashMap<>();
            cbInfo.put("state", cb.getState().name());
            cbInfo.put("failureRate", cb.getMetrics().getFailureRate());
            cbInfo.put("slowCallRate", cb.getMetrics().getSlowCallRate());
            cbInfo.put("numberOfBufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            cbInfo.put("numberOfSuccessfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
            cbInfo.put("numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls());
            result.put(cb.getName(), cbInfo);
        });
        return ResponseEntity.ok(result);
    }

    /** Returns state of a specific circuit breaker by name */
    @GetMapping("/circuit-breakers/{name}")
    public ResponseEntity<Map<String, Object>> getCircuitBreaker(@PathVariable String name) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            Map<String, Object> info = new HashMap<>();
            info.put("name", cb.getName());
            info.put("state", cb.getState().name());
            info.put("failureRate", cb.getMetrics().getFailureRate());
            info.put("slowCallRate", cb.getMetrics().getSlowCallRate());
            info.put("numberOfBufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            info.put("numberOfSuccessfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
            info.put("numberOfFailedCalls", cb.getMetrics().getNumberOfFailedCalls());
            info.put("notPermittedCalls", cb.getMetrics().getNumberOfNotPermittedCalls());
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Reset (force-close) a circuit breaker — for testing/ops */
    @PostMapping("/circuit-breakers/{name}/reset")
    public ResponseEntity<Map<String, String>> resetCircuitBreaker(@PathVariable String name) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            cb.reset();
            return ResponseEntity.ok(Map.of(
                    "name", name,
                    "state", cb.getState().name(),
                    "message", "Circuit breaker reset to CLOSED"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Force-open a circuit breaker — for chaos/testing */
    @PostMapping("/circuit-breakers/{name}/open")
    public ResponseEntity<Map<String, String>> openCircuitBreaker(@PathVariable String name) {
        try {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);
            cb.transitionToOpenState();
            return ResponseEntity.ok(Map.of(
                    "name", name,
                    "state", cb.getState().name(),
                    "message", "Circuit breaker forced OPEN"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
