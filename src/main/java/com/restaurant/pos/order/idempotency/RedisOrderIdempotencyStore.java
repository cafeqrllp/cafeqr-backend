package com.restaurant.pos.order.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed implementation of OrderIdempotencyStore with automatic In-Memory fallback
 * when Redis is unavailable (local environment or Redis downtime).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOrderIdempotencyStore implements OrderIdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "idempotency:order:";
    private static final Duration TTL = Duration.ofHours(24);

    // In-memory fallback stores when Redis is down or unavailable
    private final Map<String, String> inMemoryCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> inMemoryLocks = new ConcurrentHashMap<>();

    @Override
    public <T> T get(String key, Class<T> responseClass) {
        try {
            String json = redisTemplate.opsForValue().get(PREFIX + key);
            if (json == null) {
                return getFromInMemory(key, responseClass);
            }
            return objectMapper.readValue(json, responseClass);
        } catch (Exception e) {
            log.warn("Redis unavailable — checking in-memory fallback | key={}", key);
            return getFromInMemory(key, responseClass);
        }
    }

    private <T> T getFromInMemory(String key, Class<T> responseClass) {
        try {
            String json = inMemoryCache.get(key);
            if (json == null) return null;
            return objectMapper.readValue(json, responseClass);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public <T> void put(String key, T response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            inMemoryCache.put(key, json);
            redisTemplate.opsForValue().set(PREFIX + key, json, TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable — cached response in in-memory fallback | key={}", key);
        }
    }

    @Override
    public boolean acquireLock(String key, Duration ttl) {
        try {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(PREFIX + key + ":lock", "LOCKED", ttl);
            if (success != null && success) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Redis unavailable — using in-memory lock fallback | key={}", key);
        }

        // In-memory lock fallback
        Instant now = Instant.now();
        Instant existingLockExpiry = inMemoryLocks.get(key);
        if (existingLockExpiry != null && existingLockExpiry.isAfter(now)) {
            return false; // Lock already held
        }
        inMemoryLocks.put(key, now.plus(ttl));
        return true;
    }

    @Override
    public void releaseLock(String key) {
        try {
            redisTemplate.delete(PREFIX + key + ":lock");
        } catch (Exception e) {
            log.warn("Redis unavailable — releasing in-memory lock fallback | key={}", key);
        }
        inMemoryLocks.remove(key);
    }
}
