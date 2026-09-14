package com.restaurant.pos.cache.redis;

import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Optional;

/**
 * Common Redis cache service abstraction.
 * <p>
 * Decouples business domains (POS, Orders, Inventory, Products, etc.) from low-level
 * Redis templates, connection lifecycles, circuit breaker state, and Jackson serialization.
 * </p>
 */
public interface RedisCacheService {

    /**
     * Retrieves and deserializes a cached value into the specified class type.
     */
    <T> Optional<T> get(String key, Class<T> clazz);

    /**
     * Retrieves and deserializes a complex/generic cached value using Jackson TypeReference.
     */
    <T> Optional<T> get(String key, TypeReference<T> typeRef);

    /**
     * Retrieves the raw string value from cache.
     */
    Optional<String> getString(String key);

    /**
     * Serializes and writes a value to cache with the specified TTL.
     */
    void put(String key, Object value, Duration ttl);

    /**
     * Writes a raw string value to cache with the specified TTL.
     */
    void putString(String key, String value, Duration ttl);

    /**
     * Evicts a single key from cache.
     *
     * @return {@code true} if deleted successfully, {@code false} otherwise
     */
    boolean delete(String key);

    /**
     * Safely evicts all keys matching the prefix via non-blocking SCAN.
     *
     * @return count of keys deleted
     */
    long deleteByPrefix(String prefix);

    /**
     * Atomically increments the numeric counter stored at the key.
     *
     * @return the new value after increment, or null if Redis unavailable
     */
    Long increment(String key);

    /**
     * Checks if Redis is configured, connected, and not in circuit breaker backoff.
     */
    boolean isAvailable();
}
