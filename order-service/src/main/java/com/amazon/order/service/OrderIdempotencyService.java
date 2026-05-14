package com.amazon.order.service;

import com.amazon.order.entity.Order;
import com.amazon.order.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Order Idempotency Service - Production Ready
 *
 * Prevents duplicate order creation using distributed locking with Redis.
 *
 * Key Features:
 * - Acquires distributed lock BEFORE checking for duplicates (prevents race conditions)
 * - Polls for order completion when lock is held by another request
 * - Falls back to database when cache misses
 * - Automatic lock cleanup on success or failure
 *
 * Lock-First Strategy:
 * 1. Try to acquire lock first
 * 2. If lock held by another request → poll until order appears
 * 3. If lock acquired → check for duplicates with lock protection
 * 4. If duplicate found → return it and release lock
 * 5. If new request → keep lock until order is created
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderIdempotencyService {

    private final RedisTemplate<String, String> redisTemplate;
    private final OrderRepository orderRepository;

    @Value("${order.idempotency.ttl-seconds:86400}")
    private long ttlSeconds;

    private static final String KEY_PREFIX = "idempotency:order:";
    private static final int MAX_POLL_ATTEMPTS = 50;  // 50 * 100ms = 5 seconds max wait
    private static final long POLL_INTERVAL_MS = 100;  // Check every 100ms
    private static final int LOCK_TTL_SECONDS = 30;    // Lock expires after 30 seconds

    @PostConstruct
    public void init() {
        log.info("════════════════════════════════════════════════════════");
        log.info("📋 OrderIdempotencyService Configuration");
        log.info("   Cache TTL: {} seconds ({} hours)", ttlSeconds, ttlSeconds / 3600.0);
        log.info("   Lock TTL: {} seconds", LOCK_TTL_SECONDS);
        log.info("   Max poll attempts: {} ({}ms total)",
                MAX_POLL_ATTEMPTS, MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS);
        log.info("════════════════════════════════════════════════════════");
    }

    /**
     * Check and acquire idempotency lock using lock-first strategy
     *
     * This method guarantees that only ONE request can create an order for a given
     * idempotency key by acquiring a distributed lock BEFORE checking for duplicates.
     *
     * @param userId User creating the order
     * @param idempotencyKey Unique key from request header
     * @return Existing order ID if duplicate, null if new request (with lock held)
     */
    public String checkAndAcquire(UUID userId, String idempotencyKey) {
        String cacheKey = buildKey(userId, idempotencyKey);
        String lockKey = cacheKey + ":lock";

        // ═══════════════════════════════════════════════════════════════
        // ⭐ STEP 1: ACQUIRE LOCK FIRST (prevents race conditions)
        // ═══════════════════════════════════════════════════════════════
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "processing", Duration.ofSeconds(LOCK_TTL_SECONDS));

        if (Boolean.FALSE.equals(lockAcquired)) {
            // Lock is held by another request - this is a duplicate request
            // Poll until the first request completes and the order appears
            log.info("⏳ Lock already held for: {}. Polling for order creation...", idempotencyKey);
            return pollForOrder(userId, idempotencyKey, cacheKey);
        }

        // ═══════════════════════════════════════════════════════════════
        // ⭐ STEP 2: Lock acquired - NOW check for duplicates (with lock protection)
        // ═══════════════════════════════════════════════════════════════
        try {
            // Check Redis cache (fast path)
            String cachedOrderId = redisTemplate.opsForValue().get(cacheKey);
            if (cachedOrderId != null) {
                log.info("🔄 Idempotency HIT in cache: {} → Order: {}", idempotencyKey, cachedOrderId);
                // Duplicate found - release lock and return existing order
                releaseLockInternal(lockKey, idempotencyKey);
                return cachedOrderId;
            }

            // Check database (cache miss - might be Redis restart or TTL expired)
            Optional<Order> existingOrder = orderRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey);

            if (existingOrder.isPresent()) {
                String orderId = existingOrder.get().getId().toString();
                log.info("🔄 Idempotency HIT in DB (cache miss): {} → Order: {}",
                        idempotencyKey, orderId);

                // Rebuild cache for next time
                redisTemplate.opsForValue().set(cacheKey, orderId, Duration.ofSeconds(ttlSeconds));
                log.info("💾 Rebuilt cache with TTL: {} seconds", ttlSeconds);

                // Duplicate found - release lock and return existing order
                releaseLockInternal(lockKey, idempotencyKey);
                return orderId;
            }

            // ═══════════════════════════════════════════════════════════════
            // ⭐ STEP 3: No duplicate found - this is a NEW request
            // ═══════════════════════════════════════════════════════════════
            log.info("✅ New idempotency key - lock acquired: {}", idempotencyKey);

            // Return null to signal "new request"
            // Lock stays held and will be released by storeOrderId() after order creation
            return null;

        } catch (Exception e) {
            // On any error during duplicate check, release lock immediately
            log.error("❌ Error during duplicate check - releasing lock for: {}", idempotencyKey, e);
            releaseLockInternal(lockKey, idempotencyKey);
            throw e;
        }
    }

    /**
     * Poll for order completion when lock is held by another request
     *
     * This handles the race condition where:
     * - Request 1 acquires lock and is creating the order
     * - Request 2 arrives and finds lock held
     * - Request 2 polls until Request 1 completes and the order appears
     *
     * @param userId User ID
     * @param idempotencyKey Idempotency key
     * @param cacheKey Redis cache key
     * @return Order ID once found
     * @throws IllegalStateException if order doesn't appear within timeout
     */
    private String pollForOrder(UUID userId, String idempotencyKey, String cacheKey) {
        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            // Wait before checking
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("❌ Interrupted while waiting for order creation: {}", idempotencyKey);
                throw new RuntimeException("Interrupted while waiting for idempotency lock", e);
            }

            // Check Redis cache first (most likely to succeed)
            String cachedOrderId = redisTemplate.opsForValue().get(cacheKey);
            if (cachedOrderId != null) {
                log.info("✅ Order found in cache after {}ms wait: {} → {}",
                        attempt * POLL_INTERVAL_MS, idempotencyKey, cachedOrderId);
                return cachedOrderId;
            }

            // Check database (in case Redis write failed but DB succeeded)
            Optional<Order> existingOrder = orderRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey);

            if (existingOrder.isPresent()) {
                String orderId = existingOrder.get().getId().toString();
                log.info("✅ Order found in database after {}ms wait: {} → {}",
                        attempt * POLL_INTERVAL_MS, idempotencyKey, orderId);

                // Store in cache for next time
                redisTemplate.opsForValue().set(cacheKey, orderId, Duration.ofSeconds(ttlSeconds));

                return orderId;
            }

            // Log progress every second
            if (attempt % 10 == 0) {
                log.debug("⏳ Still waiting for order creation... ({}ms elapsed)",
                        attempt * POLL_INTERVAL_MS);
            }
        }

        // Timeout - order didn't appear within polling window
        log.error("❌ TIMEOUT: Order creation not completed within {}ms for idempotency key: {}",
                MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS, idempotencyKey);

        throw new IllegalStateException(
                String.format("Timeout waiting for order creation (waited %dms). " +
                                "The order may still be processing or may have failed.",
                        MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS)
        );
    }

    /**
     * Store order ID after successful creation and release lock
     *
     * This method:
     * 1. Stores the order ID in Redis cache with TTL
     * 2. Releases the distributed lock
     *
     * The lock MUST be held when this is called (from checkAndAcquire returning null)
     *
     * @param userId User ID
     * @param idempotencyKey Idempotency key
     * @param orderId Created order ID
     */
    public void storeOrderId(UUID userId, String idempotencyKey, UUID orderId) {
        String cacheKey = buildKey(userId, idempotencyKey);
        String lockKey = cacheKey + ":lock";

        try {
            // Store order ID in Redis with TTL
            redisTemplate.opsForValue().set(
                    cacheKey,
                    orderId.toString(),
                    Duration.ofSeconds(ttlSeconds)
            );
            log.info("💾 Stored idempotency mapping: {} → {} (TTL: {}s)",
                    idempotencyKey, orderId, ttlSeconds);

        } catch (Exception e) {
            log.error("❌ Failed to store idempotency mapping in Redis: {} → {}",
                    idempotencyKey, orderId, e);
            // Don't throw - order was created successfully, cache failure is not critical

        } finally {
            // Always release lock, even if Redis write fails
            releaseLockInternal(lockKey, idempotencyKey);
        }
    }

    /**
     * Release lock on failure (rollback scenario)
     *
     * Called when order creation fails after lock was acquired.
     * This allows other requests to retry the operation.
     *
     * @param userId User ID
     * @param idempotencyKey Idempotency key
     */
    public void releaseLock(UUID userId, String idempotencyKey) {
        String cacheKey = buildKey(userId, idempotencyKey);
        String lockKey = cacheKey + ":lock";

        releaseLockInternal(lockKey, idempotencyKey);
    }

    /**
     * Internal method to release lock
     */
    private void releaseLockInternal(String lockKey, String idempotencyKey) {
        try {
            Boolean deleted = redisTemplate.delete(lockKey);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("🔓 Released lock for: {}", idempotencyKey);
            } else {
                log.warn("⚠️  Lock key not found when releasing: {}", idempotencyKey);
            }
        } catch (Exception e) {
            log.error("❌ Error releasing lock for: {}", idempotencyKey, e);
            // Don't throw - lock will expire anyway due to TTL
        }
    }

    /**
     * Build cache key: idempotency:order:{userId}:{idempotencyKey}
     */
    private String buildKey(UUID userId, String idempotencyKey) {
        return KEY_PREFIX + userId + ":" + idempotencyKey;
    }

    /**
     * Remove idempotency record (for testing/cleanup)
     *
     * @param userId User ID
     * @param idempotencyKey Idempotency key
     */
    public void removeRecord(UUID userId, String idempotencyKey) {
        String cacheKey = buildKey(userId, idempotencyKey);
        String lockKey = cacheKey + ":lock";

        redisTemplate.delete(cacheKey);
        redisTemplate.delete(lockKey);

        log.debug("🗑️  Removed idempotency record and lock: {}", idempotencyKey);
    }

    /**
     * Get configured TTL (for debugging/monitoring)
     */
    public long getConfiguredTTL() {
        return ttlSeconds;
    }

    /**
     * Get remaining TTL for a key (for testing/debugging)
     *
     * @return TTL in seconds, -1 if no TTL set, -2 if key doesn't exist
     */
    public Long getRemainingTTL(UUID userId, String idempotencyKey) {
        String cacheKey = buildKey(userId, idempotencyKey);
        Long ttl = redisTemplate.getExpire(cacheKey);

        if (ttl != null && ttl > 0) {
            log.debug("⏱️  Remaining TTL for {}: {} seconds", idempotencyKey, ttl);
        } else if (Long.valueOf(-1L).equals(ttl)) {
            log.warn("⚠️  Key {} has no expiration!", idempotencyKey);
        } else if (Long.valueOf(-2L).equals(ttl)) {
            log.debug("🚫 Key {} does not exist", idempotencyKey);
        }

        return ttl;
    }

    /**
     * Check if order ID is cached (for testing/monitoring)
     */
    public boolean isCached(UUID userId, String idempotencyKey) {
        String cacheKey = buildKey(userId, idempotencyKey);
        Boolean exists = redisTemplate.hasKey(cacheKey);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Check if lock is currently held (for testing/monitoring)
     */
    public boolean isLockHeld(UUID userId, String idempotencyKey) {
        String cacheKey = buildKey(userId, idempotencyKey);
        String lockKey = cacheKey + ":lock";
        Boolean exists = redisTemplate.hasKey(lockKey);
        return Boolean.TRUE.equals(exists);
    }
}