package com.amazon.gateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Fallback Controller for Circuit Breaker
 *
 * Handles fallback responses when circuit breakers trip or services are unavailable.
 * Returns 503 Service Unavailable with descriptive error messages.
 *
 * FIX: previously stacked @GetMapping + @PostMapping on the same method,
 * which does not reliably register both HTTP methods (only one mapping
 * typically takes effect depending on annotation processing order). This
 * caused POST requests hitting a tripped circuit breaker to receive a
 * confusing 405 Method Not Allowed from the fallback forward, instead of
 * the intended 503 Service Unavailable — masking the real underlying
 * issue (the protected service timing out) behind an unrelated routing
 * error. Fixed by using a single @RequestMapping with an explicit method
 * array, which is the supported way to map multiple HTTP methods to one
 * handler.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping(value = "/user-service", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, String>> userServiceFallback() {
        return createFallbackResponse("User Service is currently unavailable. Please try again later.");
    }

    @RequestMapping(value = "/product-service", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, String>> productServiceFallback() {
        return createFallbackResponse("Product Service is currently unavailable. Please try again later.");
    }

    @RequestMapping(value = "/order-service", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, String>> orderServiceFallback() {
        return createFallbackResponse("Order Service is currently unavailable. Please try again later.");
    }

    @RequestMapping(value = "/payment-service", method = {RequestMethod.GET, RequestMethod.POST})
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