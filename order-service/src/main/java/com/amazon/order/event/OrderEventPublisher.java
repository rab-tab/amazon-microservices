package com.amazon.order.service;

import com.amazon.order.entity.Order;
import com.amazon.order.entity.OrderItem;
import com.amazon.order.event.OrderCreatedEvent;
import com.amazon.order.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles publishing Kafka events AFTER database transaction commits
 *
 * This component listens for domain events (like OrderCreatedEvent) and publishes
 * corresponding Kafka messages only AFTER the database transaction has successfully
 * committed. This prevents race conditions where Kafka consumers try to process
 * events for entities that haven't been persisted yet.
 *
 * Timeline with TransactionalEventListener:
 * 1. Order saved to database
 * 2. OrderCreatedEvent published (in-memory, within transaction)
 * 3. Transaction commits ← DATABASE NOW HAS THE ORDER
 * 4. @TransactionalEventListener triggered ← WE ARE HERE
 * 5. Kafka messages published
 * 6. Payment Service receives and processes
 * 7. Payment result received - Order exists in DB ✅
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";

    /**
     * Publishes Kafka events AFTER the order creation transaction commits
     *
     * This method is triggered automatically by Spring when:
     * 1. An OrderCreatedEvent is published
     * 2. The transaction successfully commits
     *
     * If the transaction rolls back, this method is NOT called.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        Order order = event.getOrder();
        String testScenario = event.getTestScenario();

        log.info("════════════════════════════════════════════════════════");
        log.info("🔔 TRANSACTION COMMITTED - Publishing Kafka events");
        log.info("   Order ID: {}", order.getId());
        log.info("   User ID: {}", order.getUserId());
        log.info("   Total Amount: {}", order.getTotalAmount());
        log.info("   Test Scenario: {}", testScenario != null ? testScenario : "none (normal flow)");

        try {
            // Publish order created event (for audit, notifications, etc.)
            publishOrderCreatedEvent(order);

            // Publish payment request to Payment Service
            publishPaymentRequest(order, testScenario);

            log.info("✅ All Kafka events published successfully");
            log.info("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("════════════════════════════════════════════════════════");
            log.error("❌ FAILED to publish Kafka events after commit", e);
            log.error("   Order ID: {}", order.getId());
            log.error("   NOTE: Transaction already committed - order exists in DB");
            log.error("   Consider implementing dead letter queue or manual retry");
            log.error("════════════════════════════════════════════════════════");

            // Note: We can't roll back the transaction here - it's already committed
            // Consider implementing:
            // 1. Dead letter queue for failed events
            // 2. Manual retry mechanism
            // 3. Monitoring/alerting for failed event publishing
        }
    }

    /**
     * Publish ORDER_CREATED event to order.events topic
     * Used for audit trail, notifications, downstream services, etc.
     */
    private void publishOrderCreatedEvent(Order order) {
        OrderEvent event = OrderEvent.builder()
                .eventType("ORDER_CREATED")
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
        log.info("📤 Published ORDER_CREATED event to {}", ORDER_EVENTS_TOPIC);
    }

    /**
     * Publish payment request to Payment Service
     *
     * @param order The order entity
     * @param testScenario Optional test scenario for automated testing
     */
    private void publishPaymentRequest(Order order, String testScenario) {
        Map<String, Object> paymentRequest = new HashMap<>();
        paymentRequest.put("orderId", order.getId().toString());
        paymentRequest.put("userId", order.getUserId().toString());
        paymentRequest.put("amount", order.getTotalAmount());
        paymentRequest.put("currency", "USD");

        // Include test scenario if present (for automated testing)
        if (testScenario != null && !testScenario.isBlank()) {
            paymentRequest.put("testScenario", testScenario);
            log.info("💳 Payment request published WITH test scenario: '{}'", testScenario);
        } else {
            log.info("💳 Payment request published (normal production flow)");
        }

        kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, order.getId().toString(), paymentRequest);
        log.info("📤 Published payment request to {}", PAYMENT_REQUEST_TOPIC);
    }
}