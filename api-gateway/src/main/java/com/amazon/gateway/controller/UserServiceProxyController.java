package com.amazon.gateway.controller;


import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserServiceProxyController {

    private static WebClient.Builder webClientBuilder = null;

    @Autowired
    public UserServiceProxyController(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    @GetMapping("/health")
    @CircuitBreaker(name = "userService", fallbackMethod = "healthFallback")
    public Mono<ResponseEntity<Map<String, Object>>> getUserHealth() {
        System.out.println("🔵 Calling user-service /health");

        return webClientBuilder.build()
                .get()
                .uri("http://localhost:8081/api/v1/users/health")
                .retrieve()
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> {
                            System.out.println("🔴 User service returned 5xx: " + response.statusCode());
                            return Mono.error(new WebClientResponseException(
                                    response.statusCode().value(),
                                    "User service error",
                                    null, null, null
                            ));
                        }
                )
                .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {})  // ← Fix here
                .map(response -> (ResponseEntity<Map<String, Object>>) response);    // ← And here
    }

    private Mono<ResponseEntity<Map<String, Object>>> healthFallback(Exception e) {
        System.out.println("🔴 FALLBACK triggered for user-service: " + e.getClass().getSimpleName());

        Map<String, Object> response = new HashMap<>();
        response.put("error", "User service is currently unavailable");
        response.put("circuitBreakerTriggered", true);

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(response));
    }

}