package com.amazon.order.service;

import com.amazon.order.dto.OrderDto;
import com.amazon.order.entity.Order;
import com.amazon.order.entity.OrderItem;
import com.amazon.order.event.OrderCreatedEvent;
import com.amazon.order.event.OrderEvent;
import com.amazon.order.exception.DuplicateOrderException;
import com.amazon.order.exception.KafkaPublishException;
import com.amazon.order.exception.OrderNotFoundException;
import com.amazon.order.exception.OrderStateException;
import com.amazon.order.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
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
    private final ApplicationEventPublisher applicationEventPublisher;  // ⭐ NEW: For transactional events

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";

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
     * @return Order response
     * @throws DuplicateOrderException if order with same idempotency key exists
     */
    public OrderDto.OrderResponse createOrder(
            OrderDto.CreateOrderRequest request,
            UUID userId,
            String idempotencyKey) {

        log.info("Creating order for user: {} with idempotency key: {}", userId, idempotencyKey);

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 1: CHECK FOR DUPLICATE (Idempotency)
        // ═══════════════════════════════════════════════════════════════════════
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);

        if (existingOrder.isPresent()) {
            log.info("🔄 Duplicate order detected for idempotency key: {}. Existing order: {}",
                    idempotencyKey, existingOrder.get().getId());

            meterRegistry.counter("orders.duplicate_detected").increment();

            throw new DuplicateOrderException(
                    "Order with this idempotency key already exists: " + idempotencyKey,
                    existingOrder.get().getId()
            );
        }

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 2: BUILD ORDER ITEMS
        // ═══════════════════════════════════════════════════════════════════════
        List<OrderItem> items = request.getItems().stream()
                .map(itemReq -> OrderItem.builder()
                        .productId(itemReq.getProductId())
                        .productName(itemReq.getProductName())
                        .quantity(itemReq.getQuantity())
                        .unitPrice(itemReq.getUnitPrice())
                        .totalPrice(itemReq.getUnitPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity())))
                        .build())
                .toList();

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 3: CALCULATE TOTAL AMOUNT
        // ═══════════════════════════════════════════════════════════════════════
        BigDecimal totalAmount = items.stream()
                .map(OrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 4: BUILD ORDER ENTITY (with idempotency key)
        // ═══════════════════════════════════════════════════════════════════════
        Order order = Order.builder()
                .userId(userId)
                .idempotencyKey(idempotencyKey)
                .items(items)
                .totalAmount(totalAmount)
                .shippingAddress(request.getShippingAddress())
                .notes(request.getNotes())
                .status(Order.OrderStatus.PENDING)
                .build();

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 5: SAVE ORDER TO DATABASE (with unique constraint protection)
        // ═══════════════════════════════════════════════════════════════════════
        try {
            order = orderRepository.save(order);
            log.info("✅ Order created: {} for user: {} with idempotency key: {}",
                    order.getId(), userId, idempotencyKey);

        } catch (DataIntegrityViolationException e) {
            log.warn("⚠️  Concurrent order creation detected for idempotency key: {}. " +
                    "Database unique constraint prevented duplicate.", idempotencyKey);

            meterRegistry.counter("orders.concurrent_duplicate_prevented").increment();

            Order concurrentOrder = orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Concurrent insert race condition for idempotency key: " + idempotencyKey));

            throw new DuplicateOrderException(
                    "Order created by concurrent request",
                    concurrentOrder.getId()
            );
        }

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 6: FAULT INJECTION - Simulate Kafka failures for testing
        // ═══════════════════════════════════════════════════════════════════════
        String faultHeader = httpServletRequest.getHeader("X-Fault");

        if (faultHeader != null && !faultHeader.isEmpty()) {
            simulateKafkaFailure(faultHeader, order);
        }

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 7: EXTRACT TEST SCENARIO HEADER
        // ═══════════════════════════════════════════════════════════════════════
        String testScenario = extractTestScenario();

        // ═══════════════════════════════════════════════════════════════════════
        // ⭐ STEP 8: PUBLISH DOMAIN EVENT (will trigger Kafka publishing AFTER commit)
        // ═══════════════════════════════════════════════════════════════════════
        log.info("🔔 Publishing OrderCreatedEvent (Kafka messages will be sent after transaction commits)");
        applicationEventPublisher.publishEvent(
                new OrderCreatedEvent(this, order, testScenario)
        );

        // ═══════════════════════════════════════════════════════════════════════
        // STEP 9: UPDATE METRICS & RETURN RESPONSE
        // ═══════════════════════════════════════════════════════════════════════
        meterRegistry.counter("orders.created").increment();

        log.info("📋 Order creation method completing - transaction will commit next");
        log.info("   → On commit, @TransactionalEventListener will publish to Kafka");

        return mapToResponse(order);

        // ← TRANSACTION COMMITS HERE
        // ↓ OrderEventPublisher.handleOrderCreated() is triggered
        // ↓ Kafka messages are published
    }

    /**
     * Extract test scenario from HTTP request header
     *
     * This allows automated tests to control payment service behavior
     * by passing X-Test-Scenario header with values like:
     * - SUCCESS: Payment succeeds
     * - FAILED: Payment fails (insufficient funds)
     * - FRAUD: Fraud detection triggered
     * - CARD_EXPIRED: Card expired error
     * - TIMEOUT: Payment service doesn't respond
     * - NETWORK_ERROR: Network error during payment
     *
     * @return Test scenario string or null if not present
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
     *
     * Supported fault types:
     * - kafka-down: Complete broker unavailability
     * - kafka-timeout: Producer timeout waiting for broker response
     * - kafka-retry-failure: Producer retry exhaustion
     * - kafka-ack-failure: Insufficient in-sync replicas (ISR)
     * - serialization-error: Message serialization failure
     * - message-too-large: Message exceeds max.message.bytes
     * - buffer-full: Producer buffer overflow
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
     *
     * ⭐ CRITICAL: This now works correctly because OrderEventPublisher ensures
     * that payment requests are only sent AFTER the order transaction commits.
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
            // Don't rethrow - would cause infinite retries
        }
    }

    /**
     * Determine if payment failure is retryable
     */
    private boolean isRetryable(String failureReason) {
        if (failureReason == null) return false;

        // Network errors and timeouts are retryable
        if (failureReason.contains("Network error") ||
                failureReason.contains("timeout")) {
            return true;
        }

        // Fraud and card expired are NOT retryable (need new payment method)
        if (failureReason.contains("Fraud") ||
                failureReason.contains("expired")) {
            return false;
        }

        // Insufficient funds might be retryable (user could add money)
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
     * Event types: ORDER_CANCELLED, ORDER_STATUS_UPDATED
     *
     * Note: ORDER_CREATED is now published via OrderEventPublisher after transaction commit
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