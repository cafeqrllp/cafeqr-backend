package com.restaurant.pos.expense.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.expense.dto.ExpenseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed implementation of IdempotencyStore with automatic in-memory fallback.
 * Prioritizes Redis when available for distributed multi-node idempotency, while gracefully
 * falling back to an in-memory store if Redis is down or unreachable.
 *
 * Key Design:
 * Expects structured keys of format: "tenant=X:org=Y:key=Z".
 * Prepends "idempotency:expense:" namespace prefix internally for Redis keys.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "idempotency:expense:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_FALLBACK_ENTRIES = 5000;

    // In-memory fallback store when Redis is down or unavailable
    private final Map<String, CacheEntry> inMemoryCache = new ConcurrentHashMap<>();

    private record CacheEntry(ExpenseResponse response, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Retrieves a cached transaction response.
     * Checks Redis first (priority). If Redis is unavailable or misses, checks in-memory fallback.
     */
    @Override
    public ExpenseResponse get(String key) {
        if (key == null) {
            return null;
        }

        try {
            String json = redisTemplate.opsForValue().get(PREFIX + key);
            if (json != null) {
                return objectMapper.readValue(json, ExpenseResponse.class);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable — checking in-memory fallback | key={}", key, e);
        }

        return getFromInMemory(key);
    }

    /**
     * Caches a successful transaction response.
     * Stores in local memory fallback first, then persists to Redis with priority.
     * If Redis is down, logs a warning and gracefully allows the transaction to succeed.
     */
    @Override
    public void put(String key, ExpenseResponse response) {
        if (key == null || response == null) {
            return;
        }

        // Always populate in-memory fallback to guarantee resiliency
        putToInMemory(key, response);

        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize ExpenseResponse for Redis idempotency store | key={}", key, e);
        } catch (Exception e) {
            log.warn("Redis unavailable — cached response in in-memory fallback | key={}", key, e);
        }
    }

    private ExpenseResponse getFromInMemory(String key) {
        CacheEntry entry = inMemoryCache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            inMemoryCache.remove(key);
            return null;
        }
        return entry.response();
    }

    private void putToInMemory(String key, ExpenseResponse response) {
        if (inMemoryCache.size() >= MAX_FALLBACK_ENTRIES) {
            inMemoryCache.entrySet().removeIf(e -> e.getValue().isExpired());
            if (inMemoryCache.size() >= MAX_FALLBACK_ENTRIES) {
                var it = inMemoryCache.keySet().iterator();
                if (it.hasNext()) {
                    inMemoryCache.remove(it.next());
                }
            }
        }
        inMemoryCache.put(key, new CacheEntry(response, Instant.now().plus(TTL)));
    }
}
