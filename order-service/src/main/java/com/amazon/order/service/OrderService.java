package com.amazon.order.service;

import com.amazon.order.dto.OrderDto;
import com.amazon.order.entity.Order;
import com.amazon.order.entity.OrderItem;
import com.amazon.order.event.OrderEvent;
import com.amazon.order.exception.KafkaPublishException;
import com.amazon.order.exception.OrderNotFoundException;
import com.amazon.order.exception.OrderStateException;
import com.amazon.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";

    /**
     * Create a new order and publish events to Kafka
     * Supports fault injection via X-Fault header for testing
     */
    public OrderDto.OrderResponse createOrder(OrderDto.CreateOrderRequest request, UUID userId) {
        // 1. Build order items
        List<OrderItem> items = request.getItems().stream()
                .map(itemReq -> OrderItem.builder()
                        .productId(itemReq.getProductId())
                        .productName(itemReq.getProductName())
                        .quantity(itemReq.getQuantity())
                        .unitPrice(itemReq.getUnitPrice())
                        .totalPrice(itemReq.getUnitPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity())))
                        .build())
                .toList();

        // 2. Calculate total amount
        BigDecimal totalAmount = items.stream()
                .map(OrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Build order entity
        Order order = Order.builder()
                .userId(userId)
                .items(items)
                .totalAmount(totalAmount)
                .shippingAddress(request.getShippingAddress())
                .notes(request.getNotes())
                .status(Order.OrderStatus.PENDING)
                .build();

        // 4. Save order to database
        order = orderRepository.save(order);
        log.info("Order created: {} for user: {}", order.getId(), userId);

        // ═══════════════════════════════════════════════════════════════════════
        // 5. FAULT INJECTION - Simulate Kafka failures for testing
        // ═══════════════════════════════════════════════════════════════════════
        String faultHeader = httpServletRequest.getHeader("X-Fault");

        if (faultHeader != null && !faultHeader.isEmpty()) {
            simulateKafkaFailure(faultHeader, order);
            // If no exception thrown above, continue to normal flow
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 6. PUBLISH EVENTS TO KAFKA (normal flow)
        // ═══════════════════════════════════════════════════════════════════════
        try {
            // Publish order created event (triggers payment saga)
            publishOrderEvent("ORDER_CREATED", order);

            // Publish payment request
            publishPaymentRequest(order);

            log.info("✅ Events published successfully for order: {}", order.getId());
            meterRegistry.counter("orders.events_published").increment();

        } catch (Exception e) {
            // Real Kafka failure (not fault injection)
            log.error("❌ Failed to publish events for order: {}", order.getId(), e);
            meterRegistry.counter("orders.kafka_publish_failed").increment();

            // Throw exception to trigger transaction rollback
            throw new KafkaPublishException("Failed to publish order events", e);
        }

        // 7. Update metrics
        meterRegistry.counter("orders.created").increment();

        // 8. Return response
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

        // Track simulated failures by type
        meterRegistry.counter("orders.kafka_failure_simulated", "type", faultType).increment();

        switch (faultType) {
            case "kafka-down":
                // Test 10a: Complete Kafka broker unavailability
                throw new KafkaPublishException(
                        "Simulated Kafka failure - broker unreachable for order: " + order.getId()
                );

            case "kafka-timeout":
                // Test 10b: Producer timeout waiting for broker
                try {
                    log.warn("Simulating producer timeout delay...");
                    Thread.sleep(100); // Simulate network delay
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new KafkaPublishException(
                        "Simulated Kafka timeout - producer timed out for order: " + order.getId()
                );

            case "kafka-retry-failure":
                // Test 10c: Producer retry exhaustion
                throw new KafkaPublishException(
                        "Simulated retry failure - max retries exceeded for order: " + order.getId()
                );

            case "kafka-ack-failure":
                // Test 10d: Acknowledgment failure (min.insync.replicas not met)
                throw new KafkaPublishException(
                        "Simulated ack failure - insufficient in-sync replicas for order: " + order.getId()
                );

            case "serialization-error":
                // Test 10e: Message serialization failure
                throw new KafkaPublishException(
                        "Simulated serialization error - cannot serialize event for order: " + order.getId()
                );

            case "message-too-large":
                // Test 10f: Message size exceeding broker limit
                throw new KafkaPublishException(
                        "Simulated message too large - event exceeds max.message.bytes for order: " + order.getId()
                );

            case "buffer-full":
                // Producer buffer overflow
                throw new KafkaPublishException(
                        "Simulated buffer full - producer buffer overflow for order: " + order.getId()
                );

            default:
                log.warn("⚠️  Unknown fault injection type: '{}' - ignoring", faultType);
                // Don't throw exception for unknown fault types
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
     */
    @KafkaListener(topics = "payment.result", groupId = "order-service")
    public void handlePaymentResult(Map<String, Object> event) {
        String orderId = (String) event.get("orderId");
        String paymentStatus = (String) event.get("status");
        String paymentId = (String) event.get("paymentId");

        if (orderId == null) {
            log.warn("Received payment result with null orderId - ignoring");
            return;
        }

        orderRepository.findById(UUID.fromString(orderId)).ifPresent(order -> {
            if ("SUCCESS".equals(paymentStatus)) {
                order.setStatus(Order.OrderStatus.CONFIRMED);
                order.setPaymentId(UUID.fromString(paymentId));
                log.info("Order {} confirmed after payment success", orderId);
                meterRegistry.counter("orders.confirmed").increment();
            } else {
                order.setStatus(Order.OrderStatus.PAYMENT_FAILED);
                log.warn("Order {} payment failed", orderId);
                meterRegistry.counter("orders.payment_failed").increment();
            }
            orderRepository.save(order);
            publishOrderEvent("ORDER_STATUS_UPDATED", order);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Publish order event to Kafka
     * Event types: ORDER_CREATED, ORDER_CANCELLED, ORDER_STATUS_UPDATED
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
     * Publish payment request to payment service
     */
    private void publishPaymentRequest(Order order) {
        Map<String, Object> paymentRequest = Map.of(
                "orderId", order.getId().toString(),
                "userId", order.getUserId().toString(),
                "amount", order.getTotalAmount(),
                "currency", "USD"
        );

        kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, order.getId().toString(), paymentRequest);
        log.info("💳 Payment request published for order: {}", order.getId());
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
                .build();
    }
}