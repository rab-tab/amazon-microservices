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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * Gateway Circuit Breaker Configuration
 *
 * CRITICAL: This configures the circuit breaker to record 5xx HTTP status codes as failures.
 * Without this, CB only records exceptions, not HTTP error responses!
 */
@Configuration
public class GatewayCircuitBreakerConfig {

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

        // Configure default behavior
        factory.configureDefault(id -> {

            // Get base config from application.yml
            CircuitBreakerConfig baseConfig = circuitBreakerRegistry
                    .getConfiguration(id)
                    .orElseGet(() -> circuitBreakerRegistry.getDefaultConfig());

            // CRITICAL: Override to record 5xx as failures
            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.from(baseConfig)
                    .recordException(throwable -> {
                        // Record WebClient 5xx responses as failures
                        if (throwable instanceof WebClientResponseException) {
                            WebClientResponseException webEx = (WebClientResponseException) throwable;
                            boolean is5xx = webEx.getStatusCode().is5xxServerError();
                            System.out.println("CB Recording exception: " + throwable.getClass().getName() +
                                    " | Status: " + webEx.getStatusCode() +
                                    " | Recording as failure: " + is5xx);
                            return is5xx;
                        }
                        // Record all other exceptions
                        System.out.println("CB Recording exception: " + throwable.getClass().getName());
                        return true;
                    })
                    .build();

            // TimeLimiter config
            TimeLimiterConfig tlConfig = TimeLimiterConfig.custom()
                    .timeoutDuration(Duration.ofSeconds(3))
                    .cancelRunningFuture(true)
                    .build();

            return new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(cbConfig)
                    .timeLimiterConfig(tlConfig)
                    .build();
        });

        return factory;
    }
}