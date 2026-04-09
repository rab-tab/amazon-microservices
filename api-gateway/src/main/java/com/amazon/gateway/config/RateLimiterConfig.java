package com.amazon.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Rate Limiter Configuration
 *
 * Provides key resolvers for rate limiting:
 * 1. IP-based: For public endpoints (register, login, products)
 * 2. User-based: For authenticated endpoints (orders, user operations)
 *
 * Rate limits are configured in application.yml per route.
 */
@Configuration
@Slf4j
public class RateLimiterConfig {

    /**
     * Rate limit by IP address (DEFAULT/PRIMARY)
     *
     * Used for public endpoints where we don't have user authentication.
     * Prevents abuse from single IP addresses.
     *
     * Usage in application.yml:
     *   key-resolver: "#{@ipKeyResolver}"
     */
    @Primary  // ← ADDED THIS - Makes this the default KeyResolver
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = exchange.getRequest()
                    .getRemoteAddress()
                    .getAddress()
                    .getHostAddress();

            log.debug("Rate limiting by IP: {}", ip);
            return Mono.just(ip);
        };
    }

    /**
     * Rate limit by user ID
     *
     * Used for authenticated endpoints.
     * Each user gets their own rate limit quota.
     *
     * Requires X-User-Id header (added by JWT filter).
     * Falls back to IP if header not present.
     *
     * Usage in application.yml:
     *   key-resolver: "#{@userKeyResolver}"
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // Try to get user ID from X-User-Id header (added by JWT filter)
            String userId = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-User-Id");

            if (userId != null && !userId.isEmpty()) {
                log.debug("Rate limiting by user ID: {}", userId);
                return Mono.just("user:" + userId);
            }

            // Fallback to IP if no user ID (shouldn't happen for protected endpoints)
            String ip = exchange.getRequest()
                    .getRemoteAddress()
                    .getAddress()
                    .getHostAddress();

            log.debug("Rate limiting by IP (no user ID): {}", ip);
            return Mono.just("ip:" + ip);
        };
    }

    /**
     * Rate limit by API key (optional - for future use)
     *
     * Can be used for third-party API integrations.
     */
    @Bean
    public KeyResolver apiKeyResolver() {
        return exchange -> {
            String apiKey = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-API-Key");

            if (apiKey != null && !apiKey.isEmpty()) {
                log.debug("Rate limiting by API key: {}", apiKey);
                return Mono.just("apikey:" + apiKey);
            }

            // Fallback to IP
            String ip = exchange.getRequest()
                    .getRemoteAddress()
                    .getAddress()
                    .getHostAddress();

            return Mono.just("ip:" + ip);
        };
    }
}