package com.amazon.payment.service;

import com.amazon.payment.dto.PaymentDto;
import com.amazon.payment.entity.Payment;
import com.amazon.payment.exception.PaymentNotFoundException;
import com.amazon.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private static final String PAYMENT_RESULT_TOPIC = "payment.result";
    private static final String NOTIFICATION_TOPIC = "notification.events";
    private final Random random = new Random();

    // Listen to order created events (Saga orchestration)
    @KafkaListener(topics = "payment.request", groupId = "payment-service")
    public void processPaymentRequest(Map<String, Object> request) {
        String orderId = (String) request.get("orderId");
        String userId = (String) request.get("userId");
        BigDecimal amount = new BigDecimal(request.get("amount").toString());

        log.info("Processing payment request for order: {}", orderId);

        Payment payment = Payment.builder()
                .orderId(UUID.fromString(orderId))
                .userId(UUID.fromString(userId))
                .amount(amount)
                .status(Payment.PaymentStatus.PROCESSING)
                .paymentMethod(Payment.PaymentMethod.CREDIT_CARD)
                .build();

        payment = paymentRepository.save(payment);

        // Simulate payment processing (90% success rate)
        boolean paymentSuccess = random.nextDouble() > 0.1;

        if (paymentSuccess) {
            payment.setStatus(Payment.PaymentStatus.SUCCESS);
            payment.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            meterRegistry.counter("payments.success").increment();
            log.info("Payment successful for order: {}", orderId);
        } else {
            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setFailureReason("Insufficient funds");
            meterRegistry.counter("payments.failed").increment();
            log.warn("Payment failed for order: {}", orderId);
        }

        payment = paymentRepository.save(payment);

        // Publish payment result to order service (saga completion)
        publishPaymentResult(payment);

        // Send notification
        publishNotificationEvent(payment, paymentSuccess);
    }

    public PaymentDto.PaymentResponse initiatePayment(PaymentDto.PaymentRequest request, UUID userId) {
        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .userId(userId)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethod(request.getPaymentMethod())
                .status(Payment.PaymentStatus.PENDING)
                .build();

        payment = paymentRepository.save(payment);
        return mapToResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDto.PaymentResponse getPaymentById(UUID id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + id));
        return mapToResponse(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDto.PaymentResponse getPaymentByOrderId(UUID orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for order: " + orderId));
        return mapToResponse(payment);
    }

    public PaymentDto.PaymentResponse refundPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        if (payment.getStatus() != Payment.PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Cannot refund payment in status: " + payment.getStatus());
        }

        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        payment = paymentRepository.save(payment);
        meterRegistry.counter("payments.refunded").increment();
        return mapToResponse(payment);
    }

    private void publishPaymentResult(Payment payment) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", payment.getOrderId().toString());
        result.put("paymentId", payment.getId().toString());
        result.put("status", payment.getStatus().name().equals("SUCCESS") ? "SUCCESS" : "FAILED");
        result.put("amount", payment.getAmount());
        result.put("transactionId", payment.getTransactionId());
        kafkaTemplate.send(PAYMENT_RESULT_TOPIC, payment.getOrderId().toString(), result);
    }

    private void publishNotificationEvent(Payment payment, boolean success) {
        Map<String, Object> notification = new HashMap<>();
        notification.put("type", success ? "PAYMENT_SUCCESS" : "PAYMENT_FAILED");
        notification.put("userId", payment.getUserId().toString());
        notification.put("orderId", payment.getOrderId().toString());
        notification.put("amount", payment.getAmount());
        notification.put("timestamp", LocalDateTime.now().toString());
        kafkaTemplate.send(NOTIFICATION_TOPIC, payment.getUserId().toString(), notification);
    }

    private PaymentDto.PaymentResponse mapToResponse(Payment payment) {
        return PaymentDto.PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .paymentMethod(payment.getPaymentMethod())
                .transactionId(payment.getTransactionId())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
