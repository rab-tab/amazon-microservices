package com.amazon.order.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps critical outbound calls with Resilience4j patterns:
 *  - CircuitBreaker:  stops cascading failures to payment/product services
 *  - Retry:           handles transient Kafka publish failures
 *  - RateLimiter:     caps order creation throughput
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResilientKafkaPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;

    private static final String PAYMENT_REQUEST_CIRCUIT = "paymentService";
    private static final String ORDER_EVENT_CIRCUIT = "orderEventPublisher";
    private static final String ORDER_RATE_LIMITER = "orderCreation";

    /**
     * Publish payment request protected by CB + Retry + RateLimiter
     */
    public void publishPaymentRequest(String topic, String key, Map<String, Object> payload) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(PAYMENT_REQUEST_CIRCUIT);
        Retry retry = retryRegistry.retry(PAYMENT_REQUEST_CIRCUIT);
        RateLimiter rl = rateLimiterRegistry.rateLimiter(ORDER_RATE_LIMITER);

        Supplier<Void> publishFn = () -> {
            kafkaTemplate.send(topic, key, payload);
            log.info("[CB:{} | Retry] Payment request published for order: {}",
                    cb.getState(), key);
            return null;
        };

        Supplier<Void> decorated = Decorators.ofSupplier(publishFn)
                .withCircuitBreaker(cb)
                .withRetry(retry)
                .withRateLimiter(rl)
                .withFallback(
                    exception -> {
                        log.error("Payment request failed after resilience4j protection for order: {}. " +
                                "Reason: {}", key, exception.getMessage());
                        // Dead letter or compensating logic could go here
                        return null;
                    }
                )
                .decorate();

        decorated.get();
    }

    /**
     * Publish order event protected by CB
     */
    public void publishOrderEvent(String topic, String key, Object payload) {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(ORDER_EVENT_CIRCUIT);
        Retry retry = retryRegistry.retry(ORDER_EVENT_CIRCUIT);

        Supplier<Void> publishFn = () -> {
            kafkaTemplate.send(topic, key, payload);
            return null;
        };

        Supplier<Void> decorated = Decorators.ofSupplier(publishFn)
                .withCircuitBreaker(cb)
                .withRetry(retry)
                .withFallback(ex -> {
                    log.error("Order event publish failed, circuit state: {}", cb.getState());
                    return null;
                })
                .decorate();

        decorated.get();
    }

    /**
     * Expose circuit breaker states for monitoring and testing
     */
    public CircuitBreaker.State getPaymentCircuitState() {
        return circuitBreakerRegistry.circuitBreaker(PAYMENT_REQUEST_CIRCUIT).getState();
    }

    public CircuitBreaker.State getOrderEventCircuitState() {
        return circuitBreakerRegistry.circuitBreaker(ORDER_EVENT_CIRCUIT).getState();
    }
}
