package com.amazon.user.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Test endpoints for timeout and performance testing
 * These endpoints are used by automated tests to verify timeout handling
 */
@RestController
@RequestMapping("/api/v1/users/test")
@Slf4j
public class UserTestController {

    /**
     * Slow endpoint that delays response
     * Used to test response timeout handling in gateway
     *
     * Usage: GET /api/v1/users/test/slow?delay=5000
     */
    @GetMapping("/slow")
    public ResponseEntity<Map<String, Object>> slowEndpoint(
            @RequestParam(defaultValue = "3000") long delay) {

        log.info("🐌 Slow endpoint called with delay: {}ms", delay);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            log.error("Sleep interrupted", e);
            Thread.currentThread().interrupt();
        }

        log.info("✅ Slow endpoint completed after {}ms", delay);

        return ResponseEntity.ok(Map.of(
                "message", "Response delayed by " + delay + "ms",
                "timestamp", System.currentTimeMillis(),
                "delay", delay
        ));
    }

    /**
     * Fast endpoint for baseline comparison
     */
    @GetMapping("/fast")
    public ResponseEntity<Map<String, Object>> fastEndpoint() {
        return ResponseEntity.ok(Map.of(
                "message", "Fast response",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Variable delay endpoint
     */
    @GetMapping("/delay/{milliseconds}")
    public ResponseEntity<Map<String, Object>> delayEndpoint(
            @PathVariable long milliseconds) {

        if (milliseconds > 30000) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Maximum delay is 30 seconds"
            ));
        }

        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return ResponseEntity.ok(Map.of(
                "message", "Delayed response",
                "delay", milliseconds
        ));
    }
}