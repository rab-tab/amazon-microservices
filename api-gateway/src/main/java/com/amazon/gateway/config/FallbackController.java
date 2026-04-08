package com.amazon.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Fallback Controller for Circuit Breaker
 *
 * Handles fallback responses when circuit breakers trip or services are unavailable.
 * Returns 503 Service Unavailable with descriptive error messages.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/user-service")
    @PostMapping("/user-service")
    public ResponseEntity<Map<String, String>> userServiceFallback() {
        return createFallbackResponse("User Service is currently unavailable. Please try again later.");
    }

    @GetMapping("/product-service")
    @PostMapping("/product-service")
    public ResponseEntity<Map<String, String>> productServiceFallback() {
        return createFallbackResponse("Product Service is currently unavailable. Please try again later.");
    }

    @GetMapping("/order-service")
    @PostMapping("/order-service")
    public ResponseEntity<Map<String, String>> orderServiceFallback() {
        return createFallbackResponse("Order Service is currently unavailable. Please try again later.");
    }

    @GetMapping("/payment-service")
    @PostMapping("/payment-service")
    public ResponseEntity<Map<String, String>> paymentServiceFallback() {
        return createFallbackResponse("Payment Service is currently unavailable. Please try again later.");
    }

    private ResponseEntity<Map<String, String>> createFallbackResponse(String message) {
        Map<String, String> response = new HashMap<>();
        response.put("error", message);
        response.put("status", "SERVICE_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}