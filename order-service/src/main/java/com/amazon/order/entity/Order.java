package com.amazon.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_order_user", columnList = "user_id"),
        @Index(name = "idx_order_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", unique = true, length = 256)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "tracking_number")
    private String trackingNumber;

    // ═══════════════════════════════════════════════════════════════════════
    // ⭐ PAYMENT FAILURE DETAILS - Already present in your code
    // ═══════════════════════════════════════════════════════════════════════

    @Column(name = "payment_failure_reason")
    private String paymentFailureReason;

    @Column(name = "payment_fraud_score")
    private Integer paymentFraudScore;

    @Column(name = "payment_transaction_id")
    private String paymentTransactionId;

    @Column(name = "payment_retryable")
    private Boolean paymentRetryable;

    // ═══════════════════════════════════════════════════════════════════════
    // OPTIMISTIC LOCKING & TIMESTAMPS
    // ═══════════════════════════════════════════════════════════════════════

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "notes")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ORDER STATUS ENUM
    // ═══════════════════════════════════════════════════════════════════════

    public enum OrderStatus {
        PENDING,
        CONFIRMED,
        PAYMENT_PROCESSING,
        PAYMENT_FAILED,
        PROCESSING,
        SHIPPED,
        DELIVERED,
        CANCELLED,
        REFUNDED
    }
}