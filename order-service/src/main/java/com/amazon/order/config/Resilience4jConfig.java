package com.amazon.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class Resilience4jConfig {

    // ─── Circuit Breaker ────────────────────────────────────────────────
    // Protects the order service from cascading failures if payment/product
    // services become unavailable.
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                    // Open after 50% failure rate
                .slowCallRateThreshold(80)                   // Open after 80% slow calls
                .slowCallDurationThreshold(Duration.ofSeconds(3)) // "slow" = >3s
                .waitDurationInOpenState(Duration.ofSeconds(30))  // Stay open 30s before half-open
                .permittedNumberOfCallsInHalfOpenState(5)    // Test with 5 calls in half-open
                .slidingWindowSize(10)                       // Evaluate last 10 calls
                .minimumNumberOfCalls(5)                     // Need at least 5 calls to evaluate
                .recordExceptions(
                    Exception.class
                )
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    // ─── Retry ─────────────────────────────────────────────────────────
    // Automatically retries transient failures (e.g. Kafka publish blip)
    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(
                    java.io.IOException.class,
                    java.util.concurrent.TimeoutException.class
                )
                .ignoreExceptions(
                    IllegalArgumentException.class
                )
                .build();

        return RetryRegistry.of(config);
    }

    // ─── Rate Limiter ───────────────────────────────────────────────────
    // Limits order creation to prevent abuse (100 req/sec per instance)
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(100)
                .timeoutDuration(Duration.ofMillis(500))
                .build();

        return RateLimiterRegistry.of(config);
    }

    // ─── Time Limiter ───────────────────────────────────────────────────
    @Bean
    public TimeLimiterConfig timeLimiterConfig() {
        return TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build();
    }
}
