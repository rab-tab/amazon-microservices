package com.amazon.order.controller;

import com.amazon.order.dto.OrderDto;
import com.amazon.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    // ═══════════════════════════════════════════════════════════════════════════
    // TEST ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/test/exception-test")
    public ResponseEntity<?> testException() {
        log.info("Test exception endpoint called");

        Map<String, Object> errorResponse = new LinkedHashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        errorResponse.put("error", "Kafka Unavailable");
        errorResponse.put("message", "Test exception from controller");
        errorResponse.put("details", "Unable to publish order event. Please try again later.");

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorResponse);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CREATE ORDER
    // ═══════════════════════════════════════════════════════════════════════════

    @PostMapping
    public ResponseEntity<?> createOrder(
            @Valid @RequestBody OrderDto.CreateOrderRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Fault", required = false) String faultType) {

        if (faultType != null) {
            log.warn("🔥 FAULT INJECTION ENABLED: {}", faultType);
        }

        log.info("Creating order for user: {} with idempotency key: {}", userId, idempotencyKey);

        // ═══════════════════════════════════════════════════════════════
        // VALIDATE USER ID
        // ═══════════════════════════════════════════════════════════════
        if (userId == null || userId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        // ═══════════════════════════════════════════════════════════════
        // GENERATE IDEMPOTENCY KEY IF NOT PROVIDED
        // ═══════════════════════════════════════════════════════════════
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
            log.debug("Auto-generated idempotency key: {}", idempotencyKey);
        }

        // ═══════════════════════════════════════════════════════════════
        // VALIDATE IDEMPOTENCY KEY FORMAT
        // ═══════════════════════════════════════════════════════════════
        if (!isValidIdempotencyKey(idempotencyKey)) {
            log.warn("Invalid idempotency key format: {}", idempotencyKey);
            return ResponseEntity.badRequest().build();
        }

        // ═══════════════════════════════════════════════════════════════
        // ⭐ FAULT INJECTION TYPE 1: PRODUCER FAILURES (Kafka Publishing)
        // These faults prevent order creation entirely - HTTP 500 returned
        // Order is NOT created, NOT saved to DB
        // ═══════════════════════════════════════════════════════════════
        if (isProducerFault(faultType)) {
            log.error("🔥 PRODUCER FAULT INJECTION: {} - Returning error without creating order", faultType);

            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("timestamp", LocalDateTime.now());
            errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("error", "Kafka Unavailable");
            errorResponse.put("message", getFaultMessage(faultType));
            errorResponse.put("details", "Unable to publish order event. Please try again later.");

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }

        try {
            // ═══════════════════════════════════════════════════════════════
            // ⭐ FAULT INJECTION TYPE 2: PAYMENT SAGA FAILURES
            // These faults are passed through to Payment Service via Kafka
            // Order IS created and saved, but payment will fail
            // Enables testing Saga compensation patterns
            // ═══════════════════════════════════════════════════════════════
            OrderService.OrderResult result = orderService.createOrder(
                    request,
                    UUID.fromString(userId),
                    idempotencyKey,
                    faultType  // ⭐ Pass payment faults to service (will be ignored if producer fault)
            );

            if (result.isDuplicate()) {
                log.info("🔄 Duplicate request - returning existing order: {} with HTTP 200",
                        result.getOrder().getId());
                return ResponseEntity.ok(result.getOrder());
            } else {
                log.info("✅ New order created: {} for user: {} with HTTP 201",
                        result.getOrder().getId(), userId);
                return ResponseEntity.status(HttpStatus.CREATED).body(result.getOrder());
            }

        } catch (DataIntegrityViolationException e) {
            log.warn("Duplicate detected by database constraint (race condition): {}", idempotencyKey);
            OrderDto.OrderResponse existingOrder = orderService.getOrderByIdempotencyKey(idempotencyKey);
            return ResponseEntity.ok(existingOrder);

        } catch (Exception e) {
            log.error("❌ Unexpected exception in createOrder", e);

            Map<String, Object> errorResponse = new LinkedHashMap<>();
            errorResponse.put("timestamp", LocalDateTime.now());
            errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
            errorResponse.put("error", "Internal Server Error");
            errorResponse.put("message", "An unexpected error occurred: " + e.getMessage());

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    /**
     * ⭐ Determine if fault is a producer-side failure
     *
     * Producer faults:
     * - Prevent order creation entirely
     * - Return HTTP 500 before calling service
     * - Used to test Kafka publishing failures
     *
     * Payment faults:
     * - Allow order creation
     * - Get passed to Payment Service via Kafka
     * - Used to test Saga compensation patterns
     */
    private boolean isProducerFault(String faultType) {
        if (faultType == null || faultType.isBlank()) {
            return false;
        }

        return switch (faultType.toLowerCase()) {
            case "kafka-down",
                    "kafka-timeout",
                    "kafka-retry-failure",
                    "kafka-ack-failure",
                    "serialization-error",
                    "message-too-large",
                    "buffer-full",
                    "quota-exceeded",
                    "batch-too-large",
                    "compression-error",
                    "schema-registry-down" -> true;
            default -> false;  // ⭐ Payment faults (payment-failure, payment-fraud, etc.) fall through
        };
    }

    /**
     * Get fault injection message based on fault type
     */
    private String getFaultMessage(String faultType) {
        return switch (faultType.toLowerCase()) {
            case "kafka-down" -> "Simulated Kafka failure - broker unreachable";
            case "kafka-timeout" -> "Simulated Kafka timeout - producer timed out";
            case "kafka-retry-failure" -> "Simulated retry failure - max retries exceeded";
            case "kafka-ack-failure" -> "Simulated ack failure - insufficient in-sync replicas";
            case "serialization-error" -> "Simulated serialization error - cannot serialize event";
            case "message-too-large" -> "Simulated message too large - event exceeds max.message.bytes";
            case "buffer-full" -> "Simulated buffer full - producer buffer overflow";
            case "quota-exceeded" -> "Simulated quota exceeded - producer exceeded quota limit";
            case "batch-too-large" -> "Simulated batch too large - batch exceeds batch.max.size";
            case "compression-error" -> "Simulated compression error - failed to compress message";
            case "schema-registry-down" -> "Simulated schema registry down - unable to validate schema";
            default -> "Simulated Kafka failure";
        };
    }

    /**
     * Validate idempotency key format
     */
    private boolean isValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return key.matches("^[a-zA-Z0-9-]{8,256}$");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET ORDER BY ID
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping("/{id}")
    public ResponseEntity<OrderDto.OrderResponse> getOrderById(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        log.info("Fetching order: {} for user: {}", id, userId);

        if (userId == null || userId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        OrderDto.OrderResponse order = orderService.getOrderById(id);

        // Verify ownership
        if (!order.getUserId().equals(UUID.fromString(userId))) {
            log.warn("User {} attempted to access order {} owned by {}",
                    userId, id, order.getUserId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(order);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GET USER ORDERS (PAGINATED)
    // ═══════════════════════════════════════════════════════════════════════════

    @GetMapping
    public ResponseEntity<OrderDto.PagedOrderResponse> getUserOrders(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.info("Fetching orders for user: {} (page: {}, size: {})", userId, page, size);

        if (userId == null || userId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        OrderDto.PagedOrderResponse orders = orderService.getUserOrders(
                UUID.fromString(userId),
                page,
                size
        );

        return ResponseEntity.ok(orders);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CANCEL ORDER
    // ═══════════════════════════════════════════════════════════════════════════

    @DeleteMapping("/{id}")
    public ResponseEntity<OrderDto.OrderResponse> cancelOrder(
            @PathVariable UUID id,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        log.info("Cancelling order: {} for user: {}", id, userId);

        if (userId == null || userId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        OrderDto.OrderResponse cancelledOrder = orderService.cancelOrder(
                id,
                UUID.fromString(userId)
        );

        return ResponseEntity.ok(cancelledOrder);
    }
}