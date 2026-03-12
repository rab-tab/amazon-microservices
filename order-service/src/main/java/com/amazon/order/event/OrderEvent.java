package com.amazon.order.event;

import com.amazon.order.entity.Order;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {
    private String eventType;
    private UUID orderId;
    private UUID userId;
    private BigDecimal totalAmount;
    private Order.OrderStatus status;
    private List<OrderItemEvent> items;
    private String shippingAddress;
    private LocalDateTime timestamp;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemEvent {
        private UUID productId;
        private Integer quantity;
        private BigDecimal unitPrice;
    }
}
