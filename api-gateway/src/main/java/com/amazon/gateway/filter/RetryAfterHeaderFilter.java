package com.amazon.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Adds a standard Retry-After header to 429 (rate-limited) responses.
 *
 * HISTORY OF THIS FILTER'S BUGS (kept as documentation — both were subtle
 * and worth not re-introducing):
 *
 * 1. First attempt used getOrder() = 100. RequestRateLimiter's per-route
 *    filter runs at order = 3 and SHORT-CIRCUITS the chain on denial
 *    (calls exchange.getResponse().setComplete() without ever calling
 *    chain.filter(exchange)). A GlobalFilter with a higher order value
 *    is more "inner" in the nested chain and is never invoked at all
 *    when the chain short-circuits before reaching it — so this filter
 *    silently never ran on any 429.
 *
 * 2. Second attempt lowered the order to 0, wrapping OUTSIDE
 *    RequestRateLimiter so chain.filter(exchange).then(...) would still
 *    fire even after an inner short-circuit. This DID get invoked, but
 *    by the time its .then(...) ran, the response was already COMMITTED
 *    (setComplete() had already triggered the actual write to the
 *    client). Mutating headers on an already-committed reactive HTTP
 *    response throws — that exception went unhandled, and WebFlux's
 *    error handling tried to write a second response over a connection
 *    that had already sent one, producing a broken 500 with
 *    "connection: close" instead of the intended 429 with the new header.
 *
 * CORRECT APPROACH: ServerHttpResponse.beforeCommit(Supplier<Mono<Void>>)
 * registers a callback that the response implementation itself invokes
 * right before it commits — regardless of which filter, at which order,
 * ultimately triggers that commit. This sidesteps filter-ordering
 * entirely: register the hook in the "pre" phase (before calling
 * chain.filter), and it correctly fires whether the eventual commit
 * happens deep in a short-circuit (RequestRateLimiter denying) or after
 * a full round-trip to the backend.
 */
@Component
@Slf4j
public class RetryAfterHeaderFilter implements GlobalFilter, Ordered {

    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();

        response.beforeCommit(() -> {
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                    && !response.getHeaders().containsKey(RETRY_AFTER_HEADER)) {

                String replenishRateHeader = response.getHeaders().getFirst(REPLENISH_RATE_HEADER);

                if (replenishRateHeader != null) {
                    try {
                        double replenishRate = Double.parseDouble(replenishRateHeader);
                        if (replenishRate > 0) {
                            int retryAfterSeconds = (int) Math.ceil(1.0 / replenishRate);
                            response.getHeaders().set(RETRY_AFTER_HEADER, String.valueOf(Math.max(1, retryAfterSeconds)));
                            log.debug("Added Retry-After: {}s (replenishRate={})", retryAfterSeconds, replenishRate);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Could not parse {} header value '{}' to compute Retry-After",
                                REPLENISH_RATE_HEADER, replenishRateHeader);
                    }
                } else {
                    log.warn("429 response missing {} header — cannot compute Retry-After", REPLENISH_RATE_HEADER);
                }
            }
            return Mono.empty();
        });

        // No .then(...) post-processing needed anymore — the beforeCommit
        // hook handles everything at the right moment. Ordering relative
        // to RequestRateLimiter no longer matters for this filter's
        // correctness, but keep it early (low order) so the hook is
        // registered well before any commit could plausibly happen.
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}