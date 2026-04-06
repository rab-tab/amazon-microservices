package com.amazon.order.config;

import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j is configured entirely via application.yml.
 *
 * WHY no @Bean registry here?
 * When you define a CircuitBreakerRegistry @Bean manually, Spring Boot's
 * auto-configuration backs off and the YAML resilience4j.* properties are
 * IGNORED. The manually created registry uses its own default thresholds,
 * making the YAML config dead code.
 *
 * Removing the beans lets Spring Boot auto-configure the registries from
 * application.yml, so:
 *   - resilience4j.circuitbreaker.instances.paymentService.*  is applied
 *   - resilience4j.ratelimiter.instances.orderCreation.*      is applied
 *   - resilience4j.retry.instances.paymentService.*           is applied
 *   - The test profile can override thresholds for fast CB transitions
 *
 * Spring Boot auto-configures:
 *   CircuitBreakerRegistry, RetryRegistry, RateLimiterRegistry, TimeLimiterRegistry
 * via spring-cloud-starter-circuitbreaker-resilience4j on the classpath.
 * No @Bean declarations needed.
 */
@Configuration
public class Resilience4jConfig {
    // intentionally empty — all config lives in application.yml
}