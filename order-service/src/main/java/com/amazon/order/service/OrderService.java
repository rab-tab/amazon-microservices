package com.amazon.order.service;

import com.amazon.order.dto.OrderDto;
import com.amazon.order.entity.Order;
import com.amazon.order.entity.OrderItem;
import com.amazon.order.event.OrderEvent;
import com.amazon.order.exception.OrderNotFoundException;
import com.amazon.order.exception.OrderStateException;
import com.amazon.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    private static final String ORDER_EVENTS_TOPIC = "order.events";
    private static final String PAYMENT_REQUEST_TOPIC = "payment.request";

    public OrderDto.OrderResponse createOrder(OrderDto.CreateOrderRequest request, UUID userId) {
        // Build order items
        List<OrderItem> items = request.getItems().stream()
                .map(itemReq -> OrderItem.builder()
                        .productId(itemReq.getProductId())
                        .productName(itemReq.getProductName())
                        .quantity(itemReq.getQuantity())
                        .unitPrice(itemReq.getUnitPrice())
                        .totalPrice(itemReq.getUnitPrice().multiply(BigDecimal.valueOf(itemReq.getQuantity())))
                        .build())
                .toList();

        BigDecimal totalAmount = items.stream()
                .map(OrderItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = Order.builder()
                .userId(userId)
                .items(items)
                .totalAmount(totalAmount)
                .shippingAddress(request.getShippingAddress())
                .notes(request.getNotes())
                .status(Order.OrderStatus.PENDING)
                .build();

        order = orderRepository.save(order);
        log.info("Order created: {} for user: {}", order.getId(), userId);

        // Publish order created event (triggers payment saga)
        publishOrderEvent("ORDER_CREATED", order);

        // Also publish payment request
        publishPaymentRequest(order);

        meterRegistry.counter("orders.created").increment();

        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderDto.OrderResponse getOrderById(UUID id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + id));
        return mapToResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderDto.PagedOrderResponse getUserOrders(UUID userId, int page, int size) {
        Page<Order> orders = orderRepository.findByUserId(userId, PageRequest.of(page, size));
        return OrderDto.PagedOrderResponse.builder()
                .orders(orders.getContent().stream().map(this::mapToResponse).toList())
                .page(orders.getNumber())
                .size(orders.getSize())
                .totalElements(orders.getTotalElements())
                .totalPages(orders.getTotalPages())
                .build();
    }

    public OrderDto.OrderResponse cancelOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));

        if (!order.getUserId().equals(userId)) {
            throw new SecurityException("Not authorized to cancel this order");
        }

        if (order.getStatus() != Order.OrderStatus.PENDING &&
            order.getStatus() != Order.OrderStatus.CONFIRMED) {
            throw new OrderStateException("Order cannot be cancelled in status: " + order.getStatus());
        }

        order.setStatus(Order.OrderStatus.CANCELLED);
        order = orderRepository.save(order);
        publishOrderEvent("ORDER_CANCELLED", order);
        return mapToResponse(order);
    }

    // Kafka listener for payment results (Saga pattern)
    @KafkaListener(topics = "payment.result", groupId = "order-service")
    public void handlePaymentResult(Map<String, Object> event) {
        String orderId = (String) event.get("orderId");
        String paymentStatus = (String) event.get("status");
        String paymentId = (String) event.get("paymentId");

        if (orderId == null) return;

        orderRepository.findById(UUID.fromString(orderId)).ifPresent(order -> {
            if ("SUCCESS".equals(paymentStatus)) {
                order.setStatus(Order.OrderStatus.CONFIRMED);
                order.setPaymentId(UUID.fromString(paymentId));
                log.info("Order {} confirmed after payment success", orderId);
                meterRegistry.counter("orders.confirmed").increment();
            } else {
                order.setStatus(Order.OrderStatus.PAYMENT_FAILED);
                log.warn("Order {} payment failed", orderId);
                meterRegistry.counter("orders.payment_failed").increment();
            }
            orderRepository.save(order);
            publishOrderEvent("ORDER_STATUS_UPDATED", order);
        });
    }

    private void publishOrderEvent(String eventType, Order order) {
        OrderEvent event = OrderEvent.builder()
                .eventType(eventType)
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
        kafkaTemplate.send(ORDER_EVENTS_TOPIC, order.getId().toString(), event);
    }

    private void publishPaymentRequest(Order order) {
        Map<String, Object> paymentRequest = Map.of(
                "orderId", order.getId().toString(),
                "userId", order.getUserId().toString(),
                "amount", order.getTotalAmount(),
                "currency", "USD"
        );
        kafkaTemplate.send(PAYMENT_REQUEST_TOPIC, order.getId().toString(), paymentRequest);
        log.info("Payment request published for order: {}", order.getId());
    }

    private OrderDto.OrderResponse mapToResponse(Order order) {
        List<OrderDto.OrderItemResponse> itemResponses = order.getItems().stream()
                .map(item -> OrderDto.OrderItemResponse.builder()
                        .id(item.getId())
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .totalPrice(item.getTotalPrice())
                        .build())
                .toList();

        return OrderDto.OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .items(itemResponses)
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .shippingAddress(order.getShippingAddress())
                .paymentId(order.getPaymentId())
                .trackingNumber(order.getTrackingNumber())
                .notes(order.getNotes())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
