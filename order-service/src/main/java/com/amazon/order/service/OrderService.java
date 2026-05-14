package com.amazon.order.service;

import com.amazon.order.dto.OrderDto;
import com.amazon.order.entity.Order;
import com.amazon.order.entity.OrderItem;
import com.amazon.order.event.OrderCreatedEvent;
import com.amazon.order.event.OrderEvent;
import com.amazon.order.exception.KafkaPublishException;
import com.amazon.order.exception.OrderNotFoundException;
import com.amazon.order.exception.OrderStateException;
import com.amazon.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final HttpServletRequest httpServletRequest;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OrderIdempotencyService idempotencyService;

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";
    private static final int MAX_POLL_ATTEMPTS = 50;  // 50 * 100ms = 5 seconds
    private static final long POLL_INTERVAL_MS = 100;

    /**
     * Result wrapper for order creation
     * Contains the order and a flag indicating if it was a duplicate request
     */
    @Getter
    @AllArgsConstructor
    public static class OrderResult {
        private final OrderDto.OrderResponse order;
        private final boolean isDuplicate;
    }

    /**
     * Create a new order and publish events to Kafka
     *
     * ⭐ IMPORTANT: Uses transactional event publishing to prevent race conditions
     * Events are published AFTER the transaction commits, ensuring the order
     * exists in the database before Kafka consumers process it.
     *
     * Supports fault injection via X-Fault header for testing
     * Supports idempotency via idempotencyKey parameter
     * Supports test scenarios via X-Test-Scenario header
     *
     * @param request Order creation request
     * @param userId User ID from gateway
     * @param idempotencyKey Unique key for duplicate detection
     * @return OrderResult containing the order and duplicate flag
     */
    public OrderResult createOrder(
            OrderDto.CreateOrderRequest request,
            UUID userId,
            String idempotencyKey) {

        log.info("Creating order for user: {} with idempotency key: {}", userId, idempotencyKey);

        // ═══════════════════════════════════════════════════════════════
        // ⭐ STEP 1: CHECK IDEMPOTENCY (Redis + DB + Lock)
        // ═══════════════════════════════════════════════════════════════
        String existingOrderId = idempotencyService.checkAndAcquire(userId, idempotencyKey);

        if (existingOrderId != null) {
            log.info("🔄 Duplicate request detected - returning existing order: {}", existingOrderId);
            meterRegistry.counter("orders.duplicate_detected").increment();

            // ⭐ Return existing order with duplicate flag = true
            OrderDto.OrderResponse existingOrder = getOrderByIdWithPolling(UUID.fromString(existingOrderId));
            return new OrderResult(existingOrder, true);
        }

        log.info("✅ New order request - lock acquired, proceeding with creation");

        // Lock acquired - proceed with order creation
        Order order = null;

        try {
            // ═══════════════════════════════════════════════════════════════
            // STEP 2-4: BUILD ORDER
            // ═══════════════════════════════════════════════════════════════
            List<OrderItem> items = request.getItems().stream()
                    .map(itemReq -> OrderItem.builder()
                            .productId(itemReq.getProductId())
                            .productName(itemReq.getProductName())
                            .quantity(itemReq.getQuantity())
                            .unitPrice(itemReq.getUnitPrice())
                            .totalPrice(itemReq.getUnitPrice()
                                    .multiply(BigDecimal.valueOf(itemReq.getQuantity())))
                            .build())
                    .toList();

            BigDecimal totalAmount = items.stream()
                    .map(OrderItem::getTotalPrice)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            order = Order.builder()
                    .userId(userId)
                    .idempotencyKey(idempotencyKey)
                    .items(items)
                    .totalAmount(totalAmount)
                    .shippingAddress(request.getShippingAddress())
                    .notes(request.getNotes())
                    .status(Order.OrderStatus.PENDING)
                    .build();

            // ═══════════════════════════════════════════════════════════════
            // STEP 5: SAVE ORDER
            // ═══════════════════════════════════════════════════════════════
            order = orderRepository.save(order);
            log.info("✅ Order created: {} for user: {}", order.getId(), userId);

            // ═══════════════════════════════════════════════════════════════
            // ⭐ STEP 6: STORE IDEMPOTENCY MAPPING (Release lock)
            // ═══════════════════════════════════════════════════════════════
            idempotencyService.storeOrderId(userId, idempotencyKey, order.getId());

            // ═══════════════════════════════════════════════════════════════
            // STEP 7-9: FAULT INJECTION, EVENTS, METRICS
            // ═══════════════════════════════════════════════════════════════
            String faultHeader = httpServletRequest.getHeader("X-Fault");
            if (faultHeader != null && !faultHeader.isEmpty()) {
                simulateKafkaFailure(faultHeader, order);
            }

            String testScenario = extractTestScenario();
            applicationEventPublisher.publishEvent(
                    new OrderCreatedEvent(this, order, testScenario)
            );

            meterRegistry.counter("orders.created").increment();

            // ⭐ Return new order with duplicate flag = false
            return new OrderResult(mapToResponse(order), false);

        } catch (Exception e) {
            // ═══════════════════════════════════════════════════════════════
            // ⭐ ROLLBACK: Release lock on failure
            // ═══════════════════════════════════════════════════════════════
            log.error("❌ Order creation failed - releasing lock", e);
            idempotencyService.releaseLock(userId, idempotencyKey);
            throw e;
        }
    }

    /**
     * ⭐ Get order by ID with polling for race conditions
     *
     * When a duplicate request is detected, Redis may have the order ID
     * before the database transaction commits. This method polls for the order
     * to handle this race condition gracefully.
     *
     * @param id Order ID
     * @return Order response
     * @throws OrderNotFoundException if order not found after polling
     */
    private OrderDto.OrderResponse getOrderByIdWithPolling(UUID id) {
        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            Optional<Order> orderOpt = orderRepository.findById(id);

            if (orderOpt.isPresent()) {
                if (attempt > 1) {
                    log.info("✅ Found order {} after {} attempts ({}ms)",
                            id, attempt, attempt * POLL_INTERVAL_MS);
                    meterRegistry.counter("orders.polling_succeeded",
                            "attempts", String.valueOf(attempt)).increment();
                }
                return mapToResponse(orderOpt.get());
            }

            // Not found yet - might be race condition
            if (attempt == 1) {
                log.debug("⏳ Order {} not found immediately, polling...", id);
            } else if (attempt % 10 == 0) {
                log.debug("⏳ Still waiting for order {} (attempt {})", id, attempt);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while polling for order: {}", id);
                break;
            }
        }

        // Still not found after polling
        log.error("❌ Order {} not found after {} attempts ({}ms)",
                id, MAX_POLL_ATTEMPTS, MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS);
        meterRegistry.counter("orders.polling_timeout").increment();
        throw new OrderNotFoundException("Order not found: " + id);
    }

    /**
     * Extract test scenario from HTTP request header
     */
    private String extractTestScenario() {
        try {
            String testScenario = httpServletRequest.getHeader("X-Test-Scenario");

            if (testScenario != null && !testScenario.isBlank()) {
                log.info("🧪 Test scenario detected in request: {}", testScenario);
                meterRegistry.counter("orders.test_scenario_used", "scenario", testScenario).increment();
                return testScenario;
            }
        } catch (Exception e) {
            log.debug("Could not extract test scenario from request", e);
        }

        return null;
    }

    /**
     * Get existing order by idempotency key
     * Used when duplicate order request is detected
     *
     * @param idempotencyKey Idempotency key
     * @return Order response
     * @throws OrderNotFoundException if order not found
     */
    @Transactional(readOnly = true)
    public OrderDto.OrderResponse getOrderByIdempotencyKey(String idempotencyKey) {
        log.debug("Fetching order by idempotency key: {}", idempotencyKey);

        Order order = orderRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new OrderNotFoundException(
                        "Order not found for idempotency key: " + idempotencyKey));

        return mapToResponse(order);
    }

    /**
     * Simulate different Kafka failure scenarios based on X-Fault header value
     */
    private void simulateKafkaFailure(String faultType, Order order) {
        log.error("🔥 FAULT INJECTION: Type='{}' for order: {}", faultType, order.getId());

        meterRegistry.counter("orders.kafka_failure_simulated", "type", faultType).increment();

        switch (faultType) {
            case "kafka-down":
                throw new KafkaPublishException(
                        "Simulated Kafka failure - broker unreachable for order: " + order.getId()
                );

            case "kafka-timeout":
                try {
                    log.warn("Simulating producer timeout delay...");
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new KafkaPublishException(
                        "Simulated Kafka timeout - producer timed out for order: " + order.getId()
                );

            case "kafka-retry-failure":
                throw new KafkaPublishException(
                        "Simulated retry failure - max retries exceeded for order: " + order.getId()
                );

            case "kafka-ack-failure":
                throw new KafkaPublishException(
                        "Simulated ack failure - insufficient in-sync replicas for order: " + order.getId()
                );

            case "serialization-error":
                throw new KafkaPublishException(
                        "Simulated serialization error - cannot serialize event for order: " + order.getId()
                );

            case "message-too-large":
                throw new KafkaPublishException(
                        "Simulated message too large - event exceeds max.message.bytes for order: " + order.getId()
                );

            case "buffer-full":
                throw new KafkaPublishException(
                        "Simulated buffer full - producer buffer overflow for order: " + order.getId()
                );

            default:
                log.warn("⚠️  Unknown fault injection type: '{}' - ignoring", faultType);
        }
    }

    /**
     * Get order by ID (standard read - no polling)
     * Use getOrderByIdWithPolling() for duplicate detection scenarios
     */
    @Transactional(readOnly = true)
    public OrderDto.OrderResponse getOrderById(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderDto.PagedOrderResponse getUserOrders(UUID userId, int page, int size) {
        Page<Order> orders = orderRepository.findByUserId(userId, PageRequest.of(page, size));
        return OrderDto.PagedOrderResponse.builder()
                .orders(orders.getContent().stream().map(this::mapToResponse).toList())
                .page(orders.getNumber())
                .size(orders.getSize())
                .totalElements(orders.getTotalElements())
                .totalPages(orders.getTotalPages())
                .build();
    }

    public OrderDto.OrderResponse cancelOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        if (!order.getUserId().equals(userId)) {
            throw new SecurityException("Not authorized to cancel this order");
        }

        if (order.getStatus() != Order.OrderStatus.PENDING &&
                order.getStatus() != Order.OrderStatus.CONFIRMED) {
            throw new OrderStateException("Order cannot be cancelled in status: " + order.getStatus());
        }

        order.setStatus(Order.OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        publishOrderEvent("ORDER_CANCELLED", order);
        return mapToResponse(order);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KAFKA LISTENER - Payment Result (Saga Pattern)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Handle payment result events from payment service
     * Updates order status based on payment success/failure
     */
    @KafkaListener(
            topics = "payment.result",
            groupId = "order-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void handlePaymentResult(String message) {
        try {
            log.info("════════════════════════════════════════════════════════");
            log.info("📨 PAYMENT RESULT RECEIVED");
            log.info("   Raw Message: {}", message);

            @SuppressWarnings("unchecked")
            Map<String, Object> event = objectMapper.readValue(message, Map.class);

            String orderId = (String) event.get("orderId");
            String paymentStatus = (String) event.get("status");
            String paymentId = (String) event.get("paymentId");
            String failureReason = (String) event.get("failureReason");
            Object fraudScoreObj = event.get("fraudScore");
            String transactionId = (String) event.get("transactionId");

            log.info("   Parsed - Order ID: {}, Status: {}", orderId, paymentStatus);

            if (orderId == null) {
                log.warn("❌ Null orderId - ignoring");
                return;
            }

            // Convert fraudScore safely
            Integer fraudScore = null;
            if (fraudScoreObj != null) {
                fraudScore = (fraudScoreObj instanceof Integer)
                        ? (Integer) fraudScoreObj
                        : Integer.parseInt(fraudScoreObj.toString());
            }

            // Find order
            log.info("🔍 Looking up order: {}", orderId);
            Optional<Order> orderOpt = orderRepository.findById(UUID.fromString(orderId));

            if (orderOpt.isEmpty()) {
                log.error("❌ ORDER NOT FOUND: {}", orderId);
                log.error("   This should NOT happen if transactional events are working correctly!");
                return;
            }

            Order order = orderOpt.get();
            log.info("✅ Found order - Current status: {}", order.getStatus());

            // Update order based on payment status
            if ("SUCCESS".equals(paymentStatus)) {
                log.info("💳 Processing SUCCESS payment");
                order.setStatus(Order.OrderStatus.CONFIRMED);
                order.setPaymentId(UUID.fromString(paymentId));
                order.setPaymentTransactionId(transactionId);
                meterRegistry.counter("orders.confirmed").increment();

            } else {
                log.info("💳 Processing FAILED payment - Reason: {}", failureReason);
                order.setStatus(Order.OrderStatus.PAYMENT_FAILED);
                order.setPaymentFailureReason(failureReason);
                order.setPaymentFraudScore(fraudScore);
                order.setPaymentRetryable(isRetryable(failureReason));
                meterRegistry.counter("orders.payment_failed",
                        "reason", failureReason != null ? failureReason : "unknown").increment();
            }

            // Save order
            log.info("💾 Saving order with new status: {}", order.getStatus());
            Order savedOrder = orderRepository.save(order);
            log.info("✅ Order saved successfully!");
            log.info("   Order ID: {}", savedOrder.getId());
            log.info("   New Status: {}", savedOrder.getStatus());
            log.info("   Payment Failure Reason: {}", savedOrder.getPaymentFailureReason());

            // Publish event
            publishOrderEvent("ORDER_STATUS_UPDATED", savedOrder);
            log.info("📤 ORDER_STATUS_UPDATED event published");
            log.info("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("════════════════════════════════════════════════════════");
            log.error("❌ EXCEPTION in handlePaymentResult", e);
            log.error("   Message: {}", message);
            log.error("════════════════════════════════════════════════════════");
        }
    }

    /**
     * Determine if payment failure is retryable
     */
    private boolean isRetryable(String failureReason) {
        if (failureReason == null) return false;

        if (failureReason.contains("Network error") ||
                failureReason.contains("timeout")) {
            return true;
        }

        if (failureReason.contains("Fraud") ||
                failureReason.contains("expired")) {
            return false;
        }

        if (failureReason.contains("Insufficient funds")) {
            return true;
        }

        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Publish order event to Kafka
     */
    private void publishOrderEvent(String eventType, Order order) {
        OrderEvent event = OrderEvent.builder()
                .eventType(eventType)
                .orderId(order.getId())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .shippingAddress(order.getShippingAddress())
                .timestamp(LocalDateTime.now())
                .items(order.getItems().stream()
                        .map(item -> OrderEvent.OrderItemEvent.builder()
                                .productId(item.getProductId())
                                .quantity(item.getQuantity())
                                .unitPrice(item.getUnitPrice())
                                .build())
                        .toList())
                .build();

        kafkaTemplate.send(ORDER_EVENTS_TOPIC, order.getId().toString(), event);
        log.info("📤 Published {} event for order: {}", eventType, order.getId());
    }

    /**
     * Map Order entity to OrderResponse DTO
     */
    private OrderDto.OrderResponse mapToResponse(Order order) {
        List<OrderDto.OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> OrderDto.OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .totalPrice(item.getTotalPrice())
                        .build())
                .toList();

        return OrderDto.OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .items(itemResponses)
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .shippingAddress(order.getShippingAddress())
                .paymentId(order.getPaymentId())
                .trackingNumber(order.getTrackingNumber())
                .notes(order.getNotes())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .paymentFailureReason(order.getPaymentFailureReason())
                .paymentFraudScore(order.getPaymentFraudScore())
                .paymentTransactionId(order.getPaymentTransactionId())
                .paymentRetryable(order.getPaymentRetryable())
                .build();
    }
}