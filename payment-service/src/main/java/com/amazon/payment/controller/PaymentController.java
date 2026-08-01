package com.amazon.payment.controller;

import com.amazon.payment.dto.PaymentDto;
import com.amazon.payment.entity.Payment;
import com.amazon.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentRepository paymentRepository;

    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentDto.PaymentResponse> getPaymentByOrderId(@PathVariable UUID orderId) {
        log.info("🧪getPaymentByOrderId()");
        return paymentRepository.findByOrderId(orderId)
                .map(this::mapToResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private PaymentDto.PaymentResponse mapToResponse(Payment payment) {
        return PaymentDto.PaymentResponse.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .userId(payment.getUserId())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())   // adjust/remove if Payment entity has no currency field — see note above
                .status(payment.getStatus())
                .paymentMethod(payment.getPaymentMethod())
                .transactionId(payment.getTransactionId())
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}