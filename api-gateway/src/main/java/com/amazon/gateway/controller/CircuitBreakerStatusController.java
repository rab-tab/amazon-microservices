package com.amazon.gateway.controller;


import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class CircuitBreakerStatusController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerStatusController(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @GetMapping("/cb/status/{name}")
    public Map<String, Object> getCircuitBreakerStatus(@PathVariable String name) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(name);

        Map<String, Object> details = new HashMap<>();
        details.put("name", cb.getName());
        details.put("state", cb.getState().toString());
        details.put("failureRate", cb.getMetrics().getFailureRate() + "%");
        details.put("bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
        details.put("failedCalls", cb.getMetrics().getNumberOfFailedCalls());
        details.put("successfulCalls", cb.getMetrics().getNumberOfSuccessfulCalls());
        details.put("notPermittedCalls", cb.getMetrics().getNumberOfNotPermittedCalls());

        return details;
    }

    @GetMapping("/cb/status")
    public Map<String, Object> getAllCircuitBreakers() {
        Map<String, Object> result = new HashMap<>();

        circuitBreakerRegistry.getAllCircuitBreakers().forEach(cb -> {
            Map<String, Object> details = new HashMap<>();
            details.put("state", cb.getState().toString());
            details.put("failureRate", cb.getMetrics().getFailureRate() + "%");
            details.put("bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());

            result.put(cb.getName(), details);
        });

        return result;
    }
}