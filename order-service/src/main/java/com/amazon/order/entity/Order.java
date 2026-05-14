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
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_order_user", columnList = "user_id"),
                @Index(name = "idx_order_status", columnList = "status"),
                @Index(name = "idx_idempotency_key", columnList = "idempotency_key"),
                @Index(name = "idx_created_at", columnList = "created_at")
        },
        uniqueConstraints = {
                // ⭐ CRITICAL: User-scoped idempotency - prevents User A from getting User B's order
                @UniqueConstraint(
                        name = "uk_user_idempotency",
                        columnNames = {"user_id", "idempotency_key"}
                )
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // ⭐ REMOVED: unique = true (now handled by composite constraint)
    // ⭐ IMPORTANT: This must NOT have @Column(unique = true) because uniqueness
    //              is scoped to (user_id, idempotency_key) combination
    @Column(name = "idempotency_key", nullable = false, length = 256)
    private String idempotencyKey;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    // ═══════════════════════════════════════════════════════════════════════
    // PAYMENT FAILURE DETAILS
    // ═══════════════════════════════════════════════════════════════════════

    @Column(name = "payment_failure_reason", columnDefinition = "TEXT")
    private String paymentFailureReason;

    @Column(name = "payment_fraud_score")
    private Integer paymentFraudScore;

    @Column(name = "payment_transaction_id", length = 100)
    private String paymentTransactionId;

    @Column(name = "payment_retryable")
    private Boolean paymentRetryable;

    // ═══════════════════════════════════════════════════════════════════════
    // OPTIMISTIC LOCKING & METADATA
    // ═══════════════════════════════════════════════════════════════════════

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
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