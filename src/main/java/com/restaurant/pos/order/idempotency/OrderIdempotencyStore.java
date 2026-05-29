package com.restaurant.pos.order.idempotency;

import java.time.Duration;

/**
 * Defines the contract for caching and retrieving idempotent responses.
 */
public interface OrderIdempotencyStore {

    /**
     * Retrieves a cached response.
     *
     * @param key           the idempotency key
     * @param responseClass the class of the expected response
     * @return the cached response, or null if not found
     */
    <T> T get(String key, Class<T> responseClass);

    /**
     * Stores a successful response against its idempotency key.
     *
     * @param key      the idempotency key
     * @param response the response to cache
     */
    <T> void put(String key, T response);

    /**
     * Attempts to acquire an in-progress lock for the given idempotency key.
     *
     * @param key the idempotency key
     * @param ttl duration for the lock to remain active
     * @return true if acquired, false if a lock or complete result is already present
     */
    boolean acquireLock(String key, Duration ttl);

    /**
     * Releases an active in-progress lock.
     *
     * @param key the idempotency key
     */
    void releaseLock(String key);
}
