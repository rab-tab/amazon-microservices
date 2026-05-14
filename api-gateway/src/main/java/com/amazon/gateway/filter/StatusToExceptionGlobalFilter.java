package com.amazon.gateway.filter;



import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * CRITICAL: Converts downstream 5xx responses to exceptions
 * so Circuit Breaker can record them as failures.
 *
 * MUST have order > -1 to run BEFORE CircuitBreaker filter
 */
@Component
public class StatusToExceptionGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange)
                .onErrorResume(ex -> {
                    // Let existing exceptions pass through
                    return Mono.error(ex);
                })
                .then(Mono.defer(() -> {
                    HttpStatus statusCode = (HttpStatus) exchange.getResponse().getStatusCode();

                    if (statusCode != null && statusCode.is5xxServerError()) {
                        System.out.println("🔴 Converting " + statusCode +
                                " to WebClientResponseException");

                        // Create WebClientResponseException so CB recognizes it
                        return Mono.error(WebClientResponseException.create(
                                statusCode.value(),
                                statusCode.getReasonPhrase(),
                                null,
                                null,
                                null
                        ));
                    }

                    return Mono.empty();
                }));
    }

    @Override
    public int getOrder() {
        return -2; // Run BEFORE CircuitBreaker filter (which is -1)
    }
}
