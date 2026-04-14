package com.amazon.order.controller;

import com.amazon.order.dto.OrderDto;
import com.amazon.order.exception.DuplicateOrderException;
import com.amazon.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;

    /**
     * Create a new order with idempotency support
     *
     * Idempotency ensures duplicate requests return the same order instead of creating duplicates.
     * This prevents double-charging customers when:
     * - User clicks submit button twice
     * - Network timeout causes automatic retry
     * - Load balancer retries failed requests
     *
     * @param request Order creation request
     * @param userId User ID from API Gateway (X-User-Id header)
     * @param idempotencyKey Optional unique key for duplicate detection (auto-generated if not provided)
     * @return 201 Created with new order, or 200 OK with existing order for duplicates
     */
    @PostMapping
    public ResponseEntity<OrderDto.OrderResponse> createOrder(
            @Valid @RequestBody OrderDto.CreateOrderRequest request,
            @RequestHeader(value="X-User-Id",required = false) String userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        log.info("Creating order for user: {} with idempotency key: {}", userId, idempotencyKey);

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 1: GENERATE IDEMPOTENCY KEY IF NOT PROVIDED
        // ═══════════════════════════════════════════════════════════════════════
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = UUID.randomUUID().toString();
            log.debug("Auto-generated idempotency key: {}", idempotencyKey);
        }
        // Handle missing userId
        if (userId == null || userId.isBlank()) {
            log.error("Missing X-User-Id header");
            return ResponseEntity.badRequest().build();
        }

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 2: VALIDATE IDEMPOTENCY KEY FORMAT
        // ═══════════════════════════════════════════════════════════════════════
        if (!isValidIdempotencyKey(idempotencyKey)) {
            log.warn("Invalid idempotency key format: {}", idempotencyKey);
            return ResponseEntity.badRequest().build();
        }

        try {
            // ═══════════════════════════════════════════════════════════════════
            // STEP 3: TRY TO CREATE NEW ORDER
            // ═══════════════════════════════════════════════════════════════════
            OrderDto.OrderResponse order = orderService.createOrder(
                    request,
                    UUID.fromString(userId),
                    idempotencyKey
            );

            log.info("✅ Order created successfully: {} for user: {}", order.getId(), userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(order);

        } catch (DuplicateOrderException e) {
            // ═══════════════════════════════════════════════════════════════════
            // STEP 4: DUPLICATE DETECTED - RETURN EXISTING ORDER
            // ═══════════════════════════════════════════════════════════════════
            log.info("🔄 Duplicate order request detected for idempotency key: {}. " +
                    "Returning existing order: {}", idempotencyKey, e.getExistingOrderId());

            // Fetch the existing order
            OrderDto.OrderResponse existingOrder = orderService.getOrderByIdempotencyKey(idempotencyKey);

            // Return 200 OK (not 201 Created) to indicate this is an existing order
            // Client can distinguish: 201 = new order created, 200 = duplicate detected
            return ResponseEntity.ok(existingOrder);
        }
    }

    /**
     * Validate idempotency key format
     *
     * Requirements:
     * - Not null or blank
     * - Length: 8-256 characters
     * - Characters: alphanumeric and hyphens only (a-z, A-Z, 0-9, -)
     *
     * Valid examples:
     * - "550e8400-e29b-41d4-a716-446655440000" (UUID)
     * - "order-2024-12345"
     * - "client-request-abc123"
     *
     * Invalid examples:
     * - "abc" (too short)
     * - "key with spaces" (contains spaces)
     * - "key@special#chars" (special characters not allowed)
     *
     * @param key Idempotency key to validate
     * @return true if valid, false otherwise
     */
    private boolean isValidIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        // UUID format or alphanumeric with hyphens, 8-256 chars
        return key.matches("^[a-zA-Z0-9-]{8,256}$");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OTHER EXISTING ENDPOINTS (unchanged)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get order by ID
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDto.OrderResponse> getOrder(
            @PathVariable UUID orderId,
            @RequestHeader("X-User-Id") String userId) {
        OrderDto.OrderResponse order = orderService.getOrderById(orderId);
        return ResponseEntity.ok(order);
    }

    /**
     * Get user's orders with pagination
     */
    @GetMapping
    public ResponseEntity<OrderDto.PagedOrderResponse> getUserOrders(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        OrderDto.PagedOrderResponse orders = orderService.getUserOrders(
                UUID.fromString(userId), page, size);
        return ResponseEntity.ok(orders);
    }

    /**
     * Cancel order
     */
    @DeleteMapping("/{orderId}")
    public ResponseEntity<OrderDto.OrderResponse> cancelOrder(
            @PathVariable UUID orderId,
            @RequestHeader("X-User-Id") String userId) {
        OrderDto.OrderResponse order = orderService.cancelOrder(orderId, UUID.fromString(userId));
        return ResponseEntity.ok(order);
    }
    @GetMapping("/test/echo-headers")
    public Map<String, String> echoHeaders(
            @RequestHeader Map<String, String> headers) {
        return headers;
    }
}