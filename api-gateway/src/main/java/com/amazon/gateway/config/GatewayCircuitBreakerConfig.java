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
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

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

        factory.configureDefault(id -> {
            CircuitBreakerConfig baseConfig = circuitBreakerRegistry
                    .getConfiguration(id)
                    .orElseGet(() -> circuitBreakerRegistry.getDefaultConfig());

            CircuitBreakerConfig cbConfig = CircuitBreakerConfig.from(baseConfig)
                    .recordException(throwable -> {
                        String exName = throwable.getClass().getSimpleName();
                        System.out.println("🔴 CB [" + id + "] Recording: " + exName);

                        if (throwable instanceof WebClientResponseException) {
                            WebClientResponseException webEx = (WebClientResponseException) throwable;
                            boolean is5xx = webEx.getStatusCode().is5xxServerError();
                            System.out.println("   → Status: " + webEx.getStatusCode() + " | Failure: " + is5xx);
                            return is5xx;
                        }

                        if (throwable instanceof TimeoutException) {
                            System.out.println("   → Timeout | Failure: true");
                            return true;
                        }

                        // CUSTOM: Our status code exception
                        if (throwable instanceof ServiceUnavailableException) {
                            System.out.println("   → Service Unavailable | Failure: true");
                            return true;
                        }

                        System.out.println("   → Generic exception | Failure: true");
                        return true;
                    })
                    .ignoreException(throwable -> {
                        if (throwable instanceof WebClientResponseException) {
                            WebClientResponseException webEx = (WebClientResponseException) throwable;
                            return webEx.getStatusCode().is4xxClientError();
                        }
                        return false;
                    })
                    .build();

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

    // Custom exception for 5xx responses
    public static class ServiceUnavailableException extends RuntimeException {
        private final HttpStatus status;

        public ServiceUnavailableException(HttpStatus status) {
            super("Service returned " + status);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }
    }
}