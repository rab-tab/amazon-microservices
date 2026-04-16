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

    /**
     * Consume payment requests as String (matches StringDeserializer)
     */
    @KafkaListener(
            topics = "payment.request",
            groupId = "payment-service",
            containerFactory = "kafkaListenerContainerFactory"  // ✅ Explicit factory
    )
    @Transactional
    public void processPaymentRequest(String message) {

        log.info("════════════════════════════════════════════════════════");
        log.info("📨 KAFKA MESSAGE RECEIVED!");
        log.info("   Raw Message: {}", message);

        try {
            // Parse JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(message, Map.class);

            String orderId = (String) request.get("orderId");
            String userId = (String) request.get("userId");
            Object amountObj = request.get("amount");

            // Handle amount (could be Integer, Double, or String)
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

            // Create payment
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

            // Publish result
            publishPaymentResult(payment);

            log.info("════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("════════════════════════════════════════════════════════");
            log.error("❌ EXCEPTION in processPaymentRequest");
            log.error("   Message: {}", message);
            log.error("   Error: ", e);
            log.error("════════════════════════════════════════════════════════");
            throw new RuntimeException("Failed to process payment", e);  // Send to DLQ
        }
    }

    /**
     * Publish payment result
     */
    private void publishPaymentResult(Payment payment) {

        log.info("📤 Publishing to payment.result...");

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", payment.getOrderId().toString());
        result.put("paymentId", payment.getId().toString());
        result.put("status", payment.getStatus() == Payment.PaymentStatus.SUCCESS ? "SUCCESS" : "FAILED");
        result.put("amount", payment.getAmount());
        result.put("transactionId", payment.getTransactionId() != null ? payment.getTransactionId() : "");

        try {
            kafkaTemplate.send(PAYMENT_RESULT_TOPIC, payment.getOrderId().toString(), result)
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