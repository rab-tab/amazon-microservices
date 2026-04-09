package com.amazon.order.exception;


/**
 * Exception thrown when Kafka event publishing fails
 * Used for both real Kafka failures and fault injection testing
 */
public class KafkaPublishException extends RuntimeException {

    public KafkaPublishException(String message) {
        super(message);
    }

    public KafkaPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
