package com.restaurant.pos.cache.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Resilient circuit breaker state for Redis operations.
 * <p>
 * Fast-fails Redis read/write operations when connection errors, timeouts,
 * or refusals occur, preventing thread starvation and cascading latency.
 * Once the cooldown period expires, the circuit transitions back to half-open,
 * allowing subsequent operations to re-test Redis connectivity.
 * </p>
 */
@Slf4j
@Component
public class RedisCircuitBreaker {

    private final AtomicLong disabledUntil = new AtomicLong(0);
    private final long backoffMs;

    public RedisCircuitBreaker(
            @Value("${redis.circuit-breaker.backoff-ms:15000}") long backoffMs) {
        this.backoffMs = backoffMs;
    }

    /**
     * Checks if Redis operations are permitted.
     *
     * @return {@code true} if Redis is active or cooldown has expired; {@code false} if tripped.
     */
    public boolean isAvailable() {
        long until = disabledUntil.get();
        if (until <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now < until) {
            return false;
        }
        // Cooldown expired, half-open state: allow traffic
        disabledUntil.set(0);
        log.info("Redis circuit breaker cooldown expired ({}ms). Re-enabling Redis operations.", backoffMs);
        return true;
    }

    /**
     * Trips the circuit breaker due to an operational failure.
     *
     * @param operation the operation that failed (e.g. GET, SET, SCAN)
     * @param key       the Redis key or pattern involved
     * @param cause     the exception caught
     */
    public void trip(String operation, String key, Throwable cause) {
        disabledUntil.set(System.currentTimeMillis() + backoffMs);
        log.warn("Redis unavailable during {} for key='{}'. Backing off Redis for {}ms. Reason: {}",
                operation, key, backoffMs, cause != null ? cause.getMessage() : "unknown");
    }

    /**
     * Explicitly resets the circuit breaker to available state.
     */
    public void reset() {
        disabledUntil.set(0);
        log.info("Redis circuit breaker explicitly reset.");
    }

    /**
     * Returns the epoch millisecond until which Redis operations are disabled.
     */
    public long getDisabledUntil() {
        return disabledUntil.get();
    }

    /**
     * Returns configured backoff duration in milliseconds.
     */
    public long getBackoffMs() {
        return backoffMs;
    }
}
