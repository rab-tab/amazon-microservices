package com.amazon.order.event;


import com.amazon.order.entity.Order;
import com.amazon.order.event.OrderCreatedEvent;
import com.amazon.order.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Listens for OrderCreatedEvent and publishes to Kafka topics.
 *
 * This decouples order creation from Kafka publishing:
 * 1. OrderService saves order to DB and publishes Spring event
 * 2. This listener receives event AFTER transaction commits
 * 3. Publishes to Kafka (order.events + payment.request)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventListener {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";

    /**
     * Handle OrderCreatedEvent - publishes to Kafka topics
     *
     * @TransactionalEventListener ensures this runs AFTER the database transaction commits
     * This prevents publishing Kafka events for orders that failed to save
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        Order order = event.getOrder();
        String testScenario = event.getTestScenario();

        log.info("════════════════════════════════════════════════════════");
        log.info("🎯 ORDER CREATED EVENT RECEIVED");
        log.info("   Order ID: {}", order.getId());
        log.info("   User ID: {}", order.getUserId());
        log.info("   Total Amount: {}", order.getTotalAmount());
        log.info("   Test Scenario: {}", testScenario != null ? testScenario : "NONE");
        log.info("════════════════════════════════════════════════════════");

        try {
            // ═══════════════════════════════════════════════════════════════
            // STEP 1: Publish ORDER_CREATED to order.events
            // ═══════════════════════════════════════════════════════════════
            OrderEvent orderEvent = OrderEvent.builder()
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

            kafkaTemplate.send(ORDER_EVENTS_TOPIC, order.getId().toString(), orderEvent)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.error("❌ Failed to publish ORDER_CREATED to order.events", exception);
                        } else {
                            log.info("✅ Published ORDER_CREATED to order.events");
                        }
                    });

            // ═══════════════════════════════════════════════════════════════
            // STEP 2: Publish PAYMENT_REQUEST to payment.request
            // ═══════════════════════════════════════════════════════════════
            Map<String, Object> paymentRequest = new HashMap<>();
            paymentRequest.put("orderId", order.getId().toString());
            paymentRequest.put("userId", order.getUserId().toString());
            paymentRequest.put("amount", order.getTotalAmount());

            // Include test scenario if present (for automated testing)
            if (testScenario != null && !testScenario.isBlank()) {
                paymentRequest.put("testScenario", testScenario);
                log.info("🧪 Including test scenario in payment request: {}", testScenario);
            }

            kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, order.getId().toString(), paymentRequest)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.error("❌ Failed to publish PAYMENT_REQUEST to payment.request", exception);
                        } else {
                            log.info("✅ Published PAYMENT_REQUEST to payment.request");
                        }
                    });

            log.info("════════════════════════════════════════════════════════");
            log.info("✅ ORDER CREATED EVENT HANDLING COMPLETE");
            log.info("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("════════════════════════════════════════════════════════");
            log.error("❌ EXCEPTION in handleOrderCreated", e);
            log.error("   Order ID: {}", order.getId());
            log.error("════════════════════════════════════════════════════════");
            // Don't throw - Kafka publishing failure should not fail the order creation
            // The order is already committed to the database
        }
    }
}
