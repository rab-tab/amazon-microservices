package com.amazon.order.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class DuplicateOrderException extends RuntimeException {

    private final UUID existingOrderId;

    public DuplicateOrderException(String message, UUID existingOrderId) {
        super(message);
        this.existingOrderId = existingOrderId;
    }
}