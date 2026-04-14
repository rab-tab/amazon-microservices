package com.amazon.order.repository;

import com.amazon.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Page<Order> findByUserId(UUID userId, Pageable pageable);
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    List<Order> findByUserIdAndStatus(UUID userId, Order.OrderStatus status);
}
