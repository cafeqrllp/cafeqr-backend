package com.restaurant.pos.order.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.exception.IdempotencyStoreException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed implementation of OrderIdempotencyStore.
 * Ensures robust idempotency matching for high-performance scale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOrderIdempotencyStore implements OrderIdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "idempotency:order:";
    private static final Duration TTL = Duration.ofHours(24);

    @Override
    public <T> T get(String key, Class<T> responseClass) {
        try {
            String json = redisTemplate.opsForValue().get(PREFIX + key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, responseClass);
        } catch (Exception e) {
            log.warn("Redis unavailable — bypassing idempotency check (failing open on read) | key={}", key, e);
            return null; // fail open on read
        }
    }

    @Override
    public <T> void put(String key, T response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
        } catch (Exception e) {
            log.error("Redis unavailable — idempotency key not stored | key={}", key, e);
            throw new IdempotencyStoreException("Idempotency store unavailable", e);
        }
    }

    /**
     * Attempts to acquire an in-flight execution lock for a given idempotency key.
     * Note: Lock keys are appended with a ":lock" suffix in Redis (e.g., "idempotency:order:tenant=X:resource=Y:key=Z:lock")
     * to strictly separate ephemeral lock states from cached response payloads.
     *
     * Fails Closed: Throwing an exception when Redis is unavailable ensures that concurrent billing or payment
     * requests are blocked rather than running concurrently, preventing accidental double billing.
     */
    @Override
    public boolean acquireLock(String key, Duration ttl) {
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(PREFIX + key + ":lock", "LOCKED", ttl);
            return success != null && success;
        } catch (Exception e) {
            log.error("Redis unavailable — failing closed on lock acquisition to prevent double processing risk | key={}", key, e);
            throw new IdempotencyStoreException("Idempotency store lock unavailable", e);
        }
    }

    @Override
    public void releaseLock(String key) {
        try {
            redisTemplate.delete(PREFIX + key + ":lock");
        } catch (Exception e) {
            log.warn("Redis unavailable — failing to release lock | key={}", key, e);
        }
    }
}
