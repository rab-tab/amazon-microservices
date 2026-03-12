package com.amazon.payment.dto;

import com.amazon.payment.entity.Payment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class PaymentDto {

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentRequest {
        @NotNull
        private UUID orderId;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal amount;

        @Builder.Default
        private String currency = "USD";

        @NotNull
        private Payment.PaymentMethod paymentMethod;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentResponse {
        private UUID id;
        private UUID orderId;
        private UUID userId;
        private BigDecimal amount;
        private String currency;
        private Payment.PaymentStatus status;
        private Payment.PaymentMethod paymentMethod;
        private String transactionId;
        private String failureReason;
        private LocalDateTime createdAt;
    }
}
