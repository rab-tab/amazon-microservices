package com.amazon.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Idempotency Service - COMPATIBLE VERSION
 *
 * Handles event deduplication using Redis as a distributed cache.
 * Uses RedisCallback for maximum compatibility across Spring Boot versions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "idempotency:";

    /**
     * Check if event has been processed before
     *
     * @param idempotencyKey Unique identifier for the event
     * @return true if event is new (should be processed), false if duplicate (skip)
     */
    public boolean isNewEvent(String idempotencyKey) {
        return isNewEvent(idempotencyKey, DEFAULT_TTL);
    }

    /**
     * Check if event has been processed before with custom TTL
     *
     * @param idempotencyKey Unique identifier for the event
     * @param ttl Time-to-live for idempotency record
     * @return true if event is new (should be processed), false if duplicate (skip)
     */
    public boolean isNewEvent(String idempotencyKey, Duration ttl) {
        String key = KEY_PREFIX + idempotencyKey;

        try {
            // SetIfAbsent (SETNX) - atomic operation
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(key, "PROCESSED", ttl);

            if (Boolean.FALSE.equals(isNew)) {
                log.info("Duplicate event detected: {}", idempotencyKey);
                return false;
            }

            log.debug("New event registered: {}", idempotencyKey);
            return true;

        } catch (Exception e) {
            log.error("Error checking idempotency for key: {}. Defaulting to processing.",
                    idempotencyKey, e);
            // Fail open - process the event
            return true;
        }
    }

    /**
     * Mark event as processed
     */
    public void markAsProcessed(String idempotencyKey) {
        markAsProcessed(idempotencyKey, DEFAULT_TTL);
    }

    /**
     * Mark event as processed with custom TTL
     */
    public void markAsProcessed(String idempotencyKey, Duration ttl) {
        String key = KEY_PREFIX + idempotencyKey;

        try {
            redisTemplate.opsForValue().set(key, "PROCESSED", ttl);
            log.debug("Event marked as processed: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Error marking event as processed: {}", idempotencyKey, e);
        }
    }

    /**
     * Check if event was already processed
     */
    public boolean wasProcessed(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;

        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Error checking if event was processed: {}", idempotencyKey, e);
            return false;
        }
    }

    /**
     * Remove idempotency record (for testing)
     */
    public void removeIdempotencyRecord(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;

        try {
            redisTemplate.delete(key);
            log.debug("Idempotency record removed: {}", idempotencyKey);
        } catch (Exception e) {
            log.error("Error removing idempotency record: {}", idempotencyKey, e);
        }
    }

    /**
     * Get remaining TTL for idempotency record - COMPATIBLE VERSION
     *
     * @param idempotencyKey Unique identifier for the event
     * @return Remaining TTL in seconds, -1 if no TTL, -2 if key doesn't exist
     */
    public Long getRemainingTTL(String idempotencyKey) {
        String key = KEY_PREFIX + idempotencyKey;

        try {
            // Use RedisCallback for direct Redis command
            return redisTemplate.execute((RedisCallback<Long>) connection ->
                    connection.ttl(key.getBytes())
            );
        } catch (Exception e) {
            log.error("Error getting TTL for idempotency record: {}", idempotencyKey, e);
            return -2L;
        }
    }

    /**
     * Build idempotency key from event components
     */
    public static String buildKey(String orderId, String eventType, String eventId) {
        return String.format("%s:%s:%s", orderId, eventType, eventId);
    }

    /**
     * Build idempotency key for order events
     */
    public static String buildOrderEventKey(String orderId, String eventType) {
        return String.format("order:%s:%s", orderId, eventType);
    }

    /**
     * Build idempotency key for payment events
     */
    public static String buildPaymentEventKey(String paymentId, String eventType) {
        return String.format("payment:%s:%s", paymentId, eventType);
    }
}