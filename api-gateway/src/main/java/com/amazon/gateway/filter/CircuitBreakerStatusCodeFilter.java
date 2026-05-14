package com.amazon.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * CRITICAL FIX: Makes Circuit Breaker record 5xx responses as failures
 *
 * Without this, Spring Cloud Gateway routes pass through 5xx responses
 * without throwing exceptions, so Resilience4j CB thinks they're successful!
 */
@Component
public class CircuitBreakerStatusCodeFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange)
                .doOnSuccess(aVoid -> {
                    HttpStatusCode statusCode = exchange.getResponse().getStatusCode();

                    if (statusCode != null && statusCode.is5xxServerError()) {
                        System.out.println("🔴 CB Filter: Detected " + statusCode +
                                " for path: " + exchange.getRequest().getPath());

                        // Throw exception BEFORE response completes
                        throw new RuntimeException("Service returned " + statusCode);
                    }
                });
    }

    @Override
    public int getOrder() {
        return -2; // Run AFTER CircuitBreaker filter (-1) but before response
    }
}
