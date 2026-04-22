package com.amazon.payment.service;

import com.amazon.payment.entity.Payment;
import com.amazon.payment.repository.PaymentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
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
            topics = "payment.request",
            groupId = "payment-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void processPaymentRequest(String message) {

        log.info("════════════════════════════════════════════════════════");
        log.info("📨 KAFKA MESSAGE RECEIVED!");
        log.info("   Raw Message: {}", message);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(message, Map.class);

            String orderId = (String) request.get("orderId");
            String userId = (String) request.get("userId");
            Object amountObj = request.get("amount");

            // ═══════════════════════════════════════════════════════════
            // ⭐ CHECK FOR TEST SCENARIO FLAG
            // ═══════════════════════════════════════════════════════════
            String testScenario = (String) request.get("testScenario");

            if (testScenario != null && !testScenario.isBlank()) {
                log.info("🧪 TEST SCENARIO DETECTED: {}", testScenario);
                handleTestScenario(orderId, userId, amountObj, testScenario);
                log.info("════════════════════════════════════════════════════════");
                return;
            }

            // ═══════════════════════════════════════════════════════════
            // NORMAL PAYMENT PROCESSING (no test scenario)
            // ═══════════════════════════════════════════════════════════

            BigDecimal amount;
            if (amountObj instanceof Number) {
                amount = BigDecimal.valueOf(((Number) amountObj).doubleValue());
            } else {
                amount = new BigDecimal(amountObj.toString());
            }

            log.info("✅ Parsed successfully");
            log.info("   Order ID: {}", orderId);
            log.info("   User ID: {}", userId);
            log.info("   Amount: {}", amount);

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

            log.info("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("════════════════════════════════════════════════════════");
            log.error("❌ EXCEPTION in processPaymentRequest");
            log.error("   Message: {}", message);
            log.error("   Error: ", e);
            log.error("════════════════════════════════════════════════════════");
            throw new RuntimeException("Failed to process payment", e);
        }
    }

    /**
     * ⭐ NEW: Handle test scenarios for automated testing
     *
     * Supported scenarios:
     * - SUCCESS: Payment succeeds
     * - FAILED / INSUFFICIENT_FUNDS: Payment fails due to insufficient funds
     * - FRAUD: Payment blocked due to fraud detection
     * - CARD_EXPIRED: Payment fails due to expired card
     * - NETWORK_ERROR: Payment fails due to network issues
     * - TIMEOUT: No response (simulates timeout)
     */
    private void handleTestScenario(String orderId, String userId, Object amountObj, String scenario) {
        BigDecimal amount;
        if (amountObj instanceof Number) {
            amount = BigDecimal.valueOf(((Number) amountObj).doubleValue());
        } else {
            amount = new BigDecimal(amountObj.toString());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("paymentId", UUID.randomUUID().toString());
        result.put("amount", amount);

        switch (scenario.toUpperCase()) {
            case "FAILED":
            case "INSUFFICIENT_FUNDS":
                result.put("status", "FAILED");
                result.put("transactionId", "");
                result.put("failureReason", "Insufficient funds");
                log.info("🧪 Simulating: FAILED payment (Insufficient funds)");
                publishTestResult(orderId, result);
                break;

            case "FRAUD":
                result.put("status", "FAILED");
                result.put("transactionId", "");
                result.put("failureReason", "Fraud detected");
                result.put("fraudScore", 95);
                log.info("🧪 Simulating: FRAUD detection");
                publishTestResult(orderId, result);
                break;

            case "CARD_EXPIRED":
                result.put("status", "FAILED");
                result.put("transactionId", "");
                result.put("failureReason", "Card expired");
                log.info("🧪 Simulating: CARD_EXPIRED");
                publishTestResult(orderId, result);
                break;

            case "NETWORK_ERROR":
                result.put("status", "FAILED");
                result.put("transactionId", "");
                result.put("failureReason", "Network error - please retry");
                log.info("🧪 Simulating: NETWORK_ERROR");
                publishTestResult(orderId, result);
                break;

            case "TIMEOUT":
                // Don't publish any result - simulate timeout
                log.info("🧪 Simulating: TIMEOUT - no result will be published");
                log.info("   Order {} will not receive payment confirmation", orderId);
                // Intentionally do nothing - no Kafka message published
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

    /**
     * Publish payment result (normal production flow)
     */
    private void publishPaymentResult(Payment payment) {
        log.info("📤 Publishing to payment.result...");

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", payment.getOrderId().toString());
        result.put("paymentId", payment.getId().toString());
        result.put("status", payment.getStatus() == Payment.PaymentStatus.SUCCESS ? "SUCCESS" : "FAILED");
        result.put("amount", payment.getAmount());
        result.put("transactionId", payment.getTransactionId() != null ? payment.getTransactionId() : "");

        if (payment.getStatus() == Payment.PaymentStatus.FAILED) {
            result.put("failureReason", payment.getFailureReason());
        }

        publishToKafka(payment.getOrderId().toString(), result);
    }

    /**
     * ⭐ NEW: Publish test result
     */
    private void publishTestResult(String orderId, Map<String, Object> result) {
        log.info("📤 Publishing TEST result to payment.result...");
        publishToKafka(orderId, result);
    }

    /**
     * Common Kafka publishing logic
     */
    private void publishToKafka(String orderId, Map<String, Object> result) {
        try {
            kafkaTemplate.send(PAYMENT_RESULT_TOPIC, orderId, result)
                    .whenComplete((sendResult, exception) -> {
                        if (exception != null) {
                            log.error("❌ Publish FAILED", exception);
                        } else {
                            log.info("✅ Published successfully to payment.result");
                        }
                    });

        } catch (Exception e) {
            log.error("❌ Exception publishing", e);
        }
    }
}