package com.amazon.payment.controller;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;


/**
 * Test Controller for Timeout Testing
 *
 * Provides endpoints that intentionally delay responses
 * to test API Gateway timeout handling (TimeLimiter)
 */
@RestController
@RequestMapping("/api/v1/test")
@Slf4j
public class TimeoutTestController {

    /**
     * Slow endpoint for timeout testing
     *
     * @param delay Delay in milliseconds (default: 1000ms)
     * @return Success message after delay
     */
    @GetMapping("/slow")
    public ResponseEntity<?> slowEndpoint(
            @RequestParam(defaultValue = "1000") int delay
    ) {
        log.info("Slow endpoint called with delay: {}ms", delay);

        try {
            // Simulate slow processing
           Thread.sleep(delay);

            log.info("Slow endpoint completed after {}ms", delay);

            return ResponseEntity.ok(Map.of(
                    "message", "Request completed successfully",
                    "delay", delay + "ms",
                    "timestamp", System.currentTimeMillis()
            ));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Slow endpoint interrupted: {}", e.getMessage());

            return ResponseEntity.status(500).body(Map.of(
                    "error", "Request interrupted",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Alternative slow endpoint (for testing different endpoints)
     */
    @GetMapping("/delay")
    public ResponseEntity<?> delayEndpoint(
            @RequestParam(defaultValue = "2000") int ms
    ) {
        log.info("Delay endpoint called with delay: {}ms", ms);

        try {
            Thread.sleep(ms);

            return ResponseEntity.ok(Map.of(
                    "message", "Delayed response",
                    "delayMs", ms,
                    "timestamp", System.currentTimeMillis()
            ));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Interrupted"
            ));
        }
    }

    /**
     * Reset/health check endpoint
     */
    @PostMapping("/delay/reset")
    public ResponseEntity<?> reset() {
        log.info("Delay test endpoints reset");
        return ResponseEntity.ok(Map.of(
                "message", "Reset successful",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Health check (fast response)
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis()
        ));
    }
}
