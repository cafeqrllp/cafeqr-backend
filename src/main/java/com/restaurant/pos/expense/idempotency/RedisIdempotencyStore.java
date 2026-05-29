package com.restaurant.pos.expense.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.expense.dto.ExpenseResponse;
import com.restaurant.pos.common.exception.IdempotencyStoreException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed implementation of IdempotencyStore.
 * Ensures FAANG-grade idempotency across multiple server nodes.
 *
 * Key Design:
 * Expects structured keys of format: "tenant=X:org=Y:key=Z".
 * Prepends "idempotency:expense:" namespace prefix internally.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "idempotency:expense:";
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * Retrieves a cached transaction response.
     * Fails open on read (log warn, return null) to prevent Redis transient errors
     * from rejecting first-time legitimate user requests.
     */
    @Override
    public ExpenseResponse get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(PREFIX + key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, ExpenseResponse.class);
        } catch (Exception e) {
            log.warn("Redis unavailable — bypassing idempotency check (failing open on read) | key={}", key, e);
            return null; // fail open on read
        }
    }

    /**
     * Caches a successful transaction response.
     * Fails closed (throws IdempotencyStoreException) to roll back the DB transaction
     * on Redis write errors, eliminating double-billing risk.
     */
    @Override
    public void put(String key, ExpenseResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ExpenseResponse for idempotency store", e);
        } catch (Exception e) {
            log.error("Redis unavailable — idempotency key not stored | key={}", key, e);
            throw new IdempotencyStoreException("Idempotency store unavailable", e);
        }
    }
}
