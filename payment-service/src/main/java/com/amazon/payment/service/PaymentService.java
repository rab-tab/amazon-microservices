package com.amazon.payment.service;

import com.amazon.payment.entity.Payment;
import com.amazon.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private final Random random = new Random();

    @KafkaListener(
            topics = "order.events",
            groupId = "payment-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void consumeOrderEvent(
            @Payload String eventJson,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("════════════════════════════════════════════════════════");
        log.info("📥 KAFKA EVENT RECEIVED from order.events");
        log.info("   Topic: {}, Partition: {}, Offset: {}", topic, partition, offset);
        log.info("   Key: {}", key);
        log.info("   Raw JSON: {}", eventJson);

        try {
            // ═══════════════════════════════════════════════════════════
            // STEP 1: Parse JSON
            // ═══════════════════════════════════════════════════════════
            JsonNode eventNode;
            try {
                eventNode = objectMapper.readTree(eventJson);
            } catch (Exception e) {
                log.error("❌ JSON PARSING FAILED - Invalid JSON format");
                log.error("   Raw content: {}", eventJson);
                log.error("   Error: {}", e.getMessage());
                throw new IllegalArgumentException("Malformed JSON: " + e.getMessage(), e);
            }

            String eventType = eventNode.path("eventType").asText();

            if (eventType.isEmpty()) {
                log.error("❌ Missing required field: eventType");
                throw new IllegalArgumentException("Missing required field: eventType");
            }

            log.info("   Event Type: {}", eventType);

            // ═══════════════════════════════════════════════════════════
            // STEP 2: Route based on event type
            // ═══════════════════════════════════════════════════════════
            if ("ORDER_CREATED".equals(eventType)) {
                processOrderCreatedEvent(eventNode);
            } else {
                log.info("   ℹ️  Ignoring event type: {}", eventType);
            }

            log.info("✅ Event processed successfully");
            log.info("════════════════════════════════════════════════════════");

        } catch (IllegalArgumentException e) {
            log.error("❌ Event validation failed - will be sent to DLQ");
            log.error("════════════════════════════════════════════════════════");
            throw e;

        } catch (Exception e) {
            log.error("❌ Business logic error processing event: {}", e.getMessage(), e);
            log.error("════════════════════════════════════════════════════════");
        }
    }

    /**
     * Process ORDER_CREATED event with IDEMPOTENCY check
     */
    private void processOrderCreatedEvent(JsonNode eventNode) {
        String orderId = eventNode.path("orderId").asText();
        String userId = eventNode.path("userId").asText();

        if (orderId.isEmpty() || userId.isEmpty()) {
            log.error("❌ Missing required fields: orderId or userId");
            throw new IllegalArgumentException("Missing required fields");
        }

        // ✅ Extract amount - handle BOTH "amount" and "totalAmount"
        // ═══════════════════════════════════════════════════════════════
        BigDecimal amount = null;

        // Try "amount" first (used in test events published directly to Kafka)
        if (eventNode.has("amount") && eventNode.path("amount").isNumber()) {
            amount = BigDecimal.valueOf(eventNode.path("amount").asDouble());
            log.info("   Amount extracted from 'amount' field: {}", amount);
        }
        // Try "totalAmount" (used by Order Service)
        else if (eventNode.has("totalAmount") && eventNode.path("totalAmount").isNumber()) {
            amount = BigDecimal.valueOf(eventNode.path("totalAmount").asDouble());
            log.info("   Amount extracted from 'totalAmount' field: {}", amount);
        }

        // If neither exists, throw error
        if (amount == null) {
            log.error("❌ Missing or invalid amount field");
            log.error("   Event has 'amount': {}", eventNode.has("amount"));
            log.error("   Event has 'totalAmount': {}", eventNode.has("totalAmount"));
            log.error("   Full event: {}", eventNode.toPrettyString());
            throw new IllegalArgumentException("Missing required field: amount or totalAmount");
        }

        log.info("   Processing ORDER_CREATED:");
        log.info("     Order ID: {}", orderId);
        log.info("     User ID: {}", userId);
        log.info("     Amount: {}", amount);
        // ═══════════════════════════════════════════════════════════════
        // ✅ IDEMPOTENCY CHECK: Does payment already exist for this order?
        // ═══════════════════════════════════════════════════════════════
        UUID orderUuid = UUID.fromString(orderId);
        Optional<Payment> existingPayment = paymentRepository.findByOrderId(orderUuid);

        if (existingPayment.isPresent()) {
            Payment existing = existingPayment.get();
            log.warn("⚠️  DUPLICATE EVENT DETECTED - Payment already exists!");
            log.warn("   Order ID: {}", orderId);
            log.warn("   Existing Payment ID: {}", existing.getId());
            log.warn("   Existing Payment Status: {}", existing.getStatus());
            log.warn("   Skipping duplicate processing (IDEMPOTENT)");

            // ✅ Re-publish payment result (in case original publish failed)
            publishPaymentResult(existing);

            return;  // ← Skip processing, return early
        }

        log.info("   ✓ No existing payment found - proceeding with payment processing");

        // Check for test scenario
        String testScenario = eventNode.path("testScenario").asText();
        if (!testScenario.isEmpty()) {
            log.info("🧪 TEST SCENARIO: {}", testScenario);
            handleTestScenario(orderId, userId, amount, testScenario);
            return;
        }

        // Normal payment processing
        processPayment(orderId, userId, amount);
    }

    /**
     * Process actual payment
     */
    private void processPayment(String orderId, String userId, BigDecimal amount) {
        Payment payment = Payment.builder()
                .orderId(UUID.fromString(orderId))
                .userId(UUID.fromString(userId))
                .amount(amount)
                .status(Payment.PaymentStatus.PROCESSING)
                .paymentMethod(Payment.PaymentMethod.CREDIT_CARD)
                .build();

        payment = paymentRepository.save(payment);
        log.info("💳 Payment created: {}", payment.getId());

        // Simulate payment (90% success)
        boolean success = random.nextDouble() > 0.1;

        if (success) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            log.info("✅ Payment SUCCESS");
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason("Insufficient funds");
            log.warn("❌ Payment FAILED");
        }

        payment = paymentRepository.save(payment);
        log.info("💾 Status saved: {}", payment.getStatus());

        publishPaymentResult(payment);
    }

    private void handleTestScenario(String orderId, String userId, BigDecimal amount, String scenario) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("amount", amount);
        result.put("timestamp", System.currentTimeMillis());

        switch (scenario.toUpperCase()) {
            case "FAILED":
            case "INSUFFICIENT_FUNDS":
                result.put("status", "FAILED");
                result.put("transactionId", "");
                result.put("failureReason", "Insufficient funds");
                log.info("🧪 Simulating: FAILED payment");
                publishTestResult(orderId, result);
                break;

            case "TIMEOUT":
                log.info("🧪 Simulating: TIMEOUT - no result published");
                break;

            case "SUCCESS":
            default:
                result.put("status", "SUCCESS");
                result.put("transactionId", "TXN-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
                log.info("🧪 Simulating: SUCCESS payment");
                publishTestResult(orderId, result);
                break;
        }
    }

    private void publishPaymentResult(Payment payment) {
        log.info("📤 Publishing to payment.result...");

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", payment.getOrderId().toString());
        result.put("paymentId", payment.getId().toString());
        result.put("status", payment.getStatus() == Payment.PaymentStatus.SUCCESS ? "SUCCESS" : "FAILED");
        result.put("amount", payment.getAmount());
        result.put("transactionId", payment.getTransactionId() != null ? payment.getTransactionId() : "");
        result.put("timestamp", System.currentTimeMillis());

        if (payment.getStatus() == Payment.PaymentStatus.FAILED) {
            result.put("failureReason", payment.getFailureReason());
        }

        publishToKafka(payment.getOrderId().toString(), result);
    }

    private void publishTestResult(String orderId, Map<String, Object> result) {
        log.info("📤 Publishing TEST result to payment.result...");
        publishToKafka(orderId, result);
    }

    private void publishToKafka(String orderId, Map<String, Object> result) {
        try {
            kafkaTemplate.send(PAYMENT_RESULT_TOPIC, orderId, result)
                    .whenComplete((sendResult, exception) -> {
                        if (exception != null) {
                            log.error("❌ Publish FAILED", exception);
                        } else {
                            log.info("✅ Published to payment.result");
                        }
                    });
        } catch (Exception e) {
            log.error("❌ Exception publishing", e);
        }
    }
}