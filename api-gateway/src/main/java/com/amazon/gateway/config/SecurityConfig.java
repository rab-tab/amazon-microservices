package com.amazon.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import java.util.Collections;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Gateway Security Configuration
 *
 * Security Strategy:
 * 1. JWT-based authentication (no sessions, no cookies)
 * 2. Public endpoints: register, login, product browsing
 * 3. Protected endpoints: orders, user profile, admin functions
 * 4. Gateway validates JWT and extracts user ID
 * 5. User ID propagated to backend services via X-User-Id header
 *
 * WHY disable CSRF?
 * - Gateway is stateless (no sessions, no cookies)
 * - JWT in Authorization header is the auth mechanism
 * - CSRF only applies to cookie-based sessions
 *
 * WHY disable httpBasic?
 * - Spring Security auto-configures HTTP Basic when spring-security is on classpath
 * - Without disabling, Spring intercepts requests with WWW-Authenticate challenge
 * - JWT validation is handled by JwtAuthenticationFilter
 */
@Configuration
@EnableWebFluxSecurity
@Slf4j
public class SecurityConfig {

    @Value("${spring.security.jwt.secret}")
    private String jwtSecret;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // JWT filter handles authentication - just permit all here
                        .anyExchange().permitAll()  // ← Simple! No context needed
                )
                .addFilterBefore(jwtAuthenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public WebFilter jwtAuthenticationFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();

            if (isPublicEndpoint(path)) {
                return chain.filter(exchange);
            }

            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return unauthorized(exchange, "Missing or invalid Authorization header");
            }

            String token = authHeader.substring(7);

            try {
                // Validate JWT
                SecretKey key = new SecretKeySpec(
                        jwtSecret.getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"
                );

                Claims claims = Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                String userId = claims.getSubject();
                String email = claims.get("email", String.class);

                log.debug("✅ JWT validated - User: {}, Email: {}", userId, email);

                // Add headers and forward
                ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(r -> r
                                .header("X-User-Id", userId)
                                .header("X-User-Email", email != null ? email : "")
                        )
                        .build();

                return chain.filter(mutatedExchange);  // ← Just forward! No context needed

            } catch (Exception e) {
                log.error("JWT validation failed: {}", e.getMessage());
                return unauthorized(exchange, "Invalid token");
            }
        };
    }

    /**
     * Check if the path is a public endpoint that doesn't require authentication
     */
    private boolean isPublicEndpoint(String path) {
        return path.equals("/api/users/register") ||
                path.equals("/api/users/login") ||
                path.equals("/api/users/health") ||
                path.startsWith("/api/products") && !path.contains("/api/products/") ||  // List only
                path.matches("/api/products/[^/]+$") ||  // Individual product view
                path.startsWith("/actuator") ||
                path.contains("/test/circuit-breaker") ||
                path.startsWith("/fallback")||

        // Test endpoints (for automated testing)
                path.contains("/test/circuit-breaker") ||
                path.contains("/api/nonexistent/service")||
                path.startsWith("/api/users/test/") ||
                path.startsWith("/api/products/test/") ||
                path.startsWith("/api/orders/test/") ||
                path.startsWith("/api/payments/test/") ;
    }

    /**
     * Return 401 Unauthorized response
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add(HttpHeaders.CONTENT_TYPE, "application/json");

        String errorJson = String.format(
                "{\"timestamp\":\"%s\",\"status\":401,\"error\":\"Unauthorized\",\"message\":\"%s\",\"path\":\"%s\"}",
                java.time.Instant.now().toString(),
                message,
                exchange.getRequest().getPath().value()
        );

        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(errorJson.getBytes(StandardCharsets.UTF_8))));
    }
}