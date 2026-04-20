package com.amazon.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerInitializer.class);
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public CircuitBreakerInitializer(CircuitBreakerRegistry circuitBreakerRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public void run(String... args) {
        log.info("========================================");
        log.info("Initializing Circuit Breakers...");

        CircuitBreaker userCB = circuitBreakerRegistry.circuitBreaker("userService");
        CircuitBreaker productCB = circuitBreakerRegistry.circuitBreaker("productService");
        CircuitBreaker orderCB = circuitBreakerRegistry.circuitBreaker("orderService");
        CircuitBreaker paymentCB = circuitBreakerRegistry.circuitBreaker("paymentService");

        log.info("Circuit Breakers Initialized:");
        log.info("  - userService: {}", userCB.getState());
        log.info("  - productService: {}", productCB.getState());
        log.info("  - orderService: {}", orderCB.getState());
        log.info("  - paymentService: {}", paymentCB.getState());
        log.info("========================================");
    }
}