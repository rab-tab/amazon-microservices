package com.amazon.gateway.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Gateway Circuit Breaker Configuration
 *
 * Configures Resilience4j circuit breakers for Spring Cloud Gateway routes.
 * Each downstream service (user, product, order, payment) gets its own CB.
 *
 * CB Configuration is in application.yml under resilience4j.circuitbreaker.configs.default
 *
 * Architecture:
 * - Layer 1 (Gateway): HTTP route protection, trips on 5xx/timeouts
 * - Layer 2 (Services): Internal operation protection (e.g., Kafka publishing)
 *
 * TIMEOUT ENFORCEMENT:
 * - TimeLimiter: 3 seconds (enforced at circuit breaker level)
 * - HTTP client: 3 seconds (transport level)
 * - Both work together to ensure fast failure
 */
@Configuration
public class GatewayCircuitBreakerConfig {

    /**
     * Create the ReactiveResilience4JCircuitBreakerFactory bean.
     *
     * CRITICAL: All 3 constructor parameters must be non-null in Spring Cloud 2023.0.0+
     * - CircuitBreakerRegistry: manages CB instances
     * - TimeLimiterRegistry: manages timeout instances
     * - Resilience4JConfigurationProperties: config holder (can be empty)
     *
     * @param circuitBreakerRegistry Auto-configured by Spring Boot from application.yml
     * @param timeLimiterRegistry Auto-configured by Spring Boot
     * @return Configured factory for creating reactive circuit breakers
     */
    @Bean
    public ReactiveResilience4JCircuitBreakerFactory reactiveCircuitBreakerFactory(
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry) {

        ReactiveResilience4JCircuitBreakerFactory factory =
                new ReactiveResilience4JCircuitBreakerFactory(
                        circuitBreakerRegistry,
                        timeLimiterRegistry,
                        new Resilience4JConfigurationProperties()
                );

        // ═══════════════════════════════════════════════════════════════════════
        // Configure default behavior with EXPLICIT TimeLimiter timeout
        // ═══════════════════════════════════════════════════════════════════════
        factory.configureDefault(id -> {
            // Get CircuitBreaker config from registry or use default config from application.yml
            CircuitBreakerConfig cbConfig = circuitBreakerRegistry
                    .getConfiguration(id)
                    .orElseGet(() -> circuitBreakerRegistry.getDefaultConfig());

            // ═══════════════════════════════════════════════════════════════════
            // CRITICAL: Explicit TimeLimiter config with 3-second timeout
            // This enforces timeout at the circuit breaker level
            // Without this, requests can hang indefinitely!
            // ═══════════════════════════════════════════════════════════════════
            TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofSeconds(3))  // 3-second timeout
                    .cancelRunningFuture(true)                // Cancel request on timeout
                    .build();

            return new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(cbConfig)
                    .timeLimiterConfig(tlConfig)  // Use explicit config (not from registry)
                    .build();
        });

        return factory;
    }
}