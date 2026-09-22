package com.amazon.product.exception;


import org.hibernate.StaleObjectStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ⭐ NEW — product-service had no exception-handling layer at all before
 * this. Every domain exception (ResourceNotFoundException,
 * InsufficientStockException, SecurityException, and — the one that
 * actually surfaced via ProductUpdateConcurrencyTest —
 * ObjectOptimisticLockingFailureException/StaleObjectStateException) was
 * propagating as a raw, unhandled 500 with a default Spring Boot error
 * body, regardless of what actually went wrong.
 *
 * Response shape (timestamp/status/error/message) matches what
 * order-service's error responses look like elsewhere in this project.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException e) {
        return buildResponse(HttpStatus.NOT_FOUND, "Not Found", e.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException e) {
        return buildResponse(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        return buildResponse(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    /**
     * ⭐ This is the one ProductUpdateConcurrencyTest actually needs.
     * With @EnableRetry now active, most concurrent-update conflicts get
     * resolved silently via retry — this handler only fires if all 3
     * attempts are genuinely exhausted under real, sustained contention,
     * mirroring order-service's equivalent handling for cancelOrder().
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, StaleObjectStateException.class})
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(Exception e) {
        return buildResponse(HttpStatus.CONFLICT, "Conflict",
                "Product was concurrently modified. Please retry.");
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}