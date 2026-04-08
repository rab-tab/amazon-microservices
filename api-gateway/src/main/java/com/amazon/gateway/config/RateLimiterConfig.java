package com.amazon.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * Rate limit by user ID (from JWT)
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // Extract user ID from X-User-Id header (added by JWT filter)
            String userId = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-User-Id");

            // If no user ID (unauthenticated), use IP address
            if (userId == null) {
                String ip = exchange.getRequest()
                        .getRemoteAddress()
                        .getAddress()
                        .getHostAddress();
                return Mono.just(ip);
            }

            return Mono.just(userId);
        };
    }

    /**
     * Rate limit by IP address (for public endpoints)
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = exchange.getRequest()
                    .getRemoteAddress()
                    .getAddress()
                    .getHostAddress();
            return Mono.just(ip);
        };
    }
}