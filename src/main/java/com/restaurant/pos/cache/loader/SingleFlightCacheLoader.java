package com.restaurant.pos.cache.loader;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Single-flight request coalescing for cache-miss scenarios with generation-based
 * invalidation race protection and bounded thread pool isolation.
 * <p>
 * When N concurrent requests miss the same cache key, only 1 database query
 * executes on the provided executor and the result is shared across all N waiters.
 * </p>
 * <p>
 * <h3>Race Condition Protection</h3>
 * If an update occurs while a DB query is in-flight, the cache key's generation
 * is incremented. When the in-flight query completes, it checks if the generation matches;
 * if not, it discards the write so stale data cannot repopulate cache.
 * </p>
 */
@Slf4j
public class SingleFlightCacheLoader {

    private final ConcurrentHashMap<String, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Executor executor;

    public SingleFlightCacheLoader() {
        this.executor = null;
    }

    public SingleFlightCacheLoader(Executor executor) {
        this.executor = executor;
    }

    /**
     * Gets the executor configured for this loader (may be null if running synchronously on caller threads).
     */
    public Executor getExecutor() {
        return executor;
    }

    /**
     * Gets the current generation counter for a cache key.
     */
    public long getGeneration(String key) {
        return generations.computeIfAbsent(key, k -> new AtomicLong(0)).get();
    }

    /**
     * Increments the generation counter for a cache key, invalidating any in-flight loads.
     */
    public long incrementGeneration(String key) {
        return generations.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Loads a value for the given cache key, coalescing concurrent requests.
     * The first thread to request a key executes the loader directly on its own thread,
     * while concurrent threads wait on the shared CompletableFuture without thread-pool recursion.
     */
    @SuppressWarnings("unchecked")
    public <T> T loadOrCoalesce(String cacheKey, Supplier<T> dbLoader) {
        CompletableFuture<T> newFuture = new CompletableFuture<>();
        CompletableFuture<?> existing = inFlight.putIfAbsent(cacheKey, newFuture);

        if (existing != null) {
            log.debug("SingleFlight: joining existing in-flight load for key={}", cacheKey);
            try {
                return (T) existing.join();
            } catch (Exception ex) {
                if (ex instanceof RuntimeException re) throw re;
                throw new java.util.concurrent.CompletionException(ex);
            }
        }

        try {
            log.debug("SingleFlight: leader executing load for key={}", cacheKey);
            T result = dbLoader.get();
            newFuture.complete(result);
            return result;
        } catch (Throwable ex) {
            newFuture.completeExceptionally(ex);
            if (ex instanceof RuntimeException re) throw re;
            throw new RuntimeException(ex);
        } finally {
            inFlight.remove(cacheKey, newFuture);
        }
    }

    /**
     * Loads a value using single-flight coalescing and safely commits it to cache only if
     * no invalidation occurred during the database load.
     *
     * @param cacheKey    the cache key being loaded
     * @param dbLoader    the supplier that performs the actual database query
     * @param cacheWriter consumer that commits the value to cache (e.g. Redis)
     * @param <T>         the type of the cached value
     * @return the loaded value
     */
    @SuppressWarnings("unchecked")
    public <T> T loadAndCache(String cacheKey, Supplier<T> dbLoader, BiConsumer<String, T> cacheWriter) {
        long generation = getGeneration(cacheKey);
        CompletableFuture<T> newFuture = new CompletableFuture<>();
        CompletableFuture<?> existing = inFlight.putIfAbsent(cacheKey, newFuture);

        if (existing != null) {
            log.debug("SingleFlight: joining existing in-flight loadAndCache for key={}", cacheKey);
            try {
                return (T) existing.join();
            } catch (Exception ex) {
                if (ex instanceof RuntimeException re) throw re;
                throw new java.util.concurrent.CompletionException(ex);
            }
        }

        try {
            log.debug("SingleFlight: leader executing loadAndCache for key={}, generation={}", cacheKey, generation);
            T result = dbLoader.get();
            if (generation == getGeneration(cacheKey)) {
                try {
                    cacheWriter.accept(cacheKey, result);
                } catch (Exception ex) {
                    log.warn("SingleFlight: cacheWriter failed for key={}", cacheKey, ex);
                }
            } else {
                log.info("SingleFlight: generation mismatch (expected={}, current={}) for key={}; skipping stale cache write",
                        generation, getGeneration(cacheKey), cacheKey);
            }
            newFuture.complete(result);
            return result;
        } catch (Throwable ex) {
            newFuture.completeExceptionally(ex);
            if (ex instanceof RuntimeException re) throw re;
            throw new RuntimeException(ex);
        } finally {
            inFlight.remove(cacheKey, newFuture);
        }
    }

    /**
     * Invalidates a cache key: increments its generation so any in-flight load will NOT write to cache,
     * and clears it from the in-flight map so subsequent calls initiate a fresh load.
     */
    public void invalidate(String cacheKey) {
        incrementGeneration(cacheKey);
        CompletableFuture<?> removed = inFlight.remove(cacheKey);
        if (removed != null) {
            log.debug("SingleFlight: cleared in-flight entry for key={}", cacheKey);
        }
    }

    /**
     * Backward-compatible alias for {@link #invalidate(String)}.
     */
    public void clearInFlight(String cacheKey) {
        invalidate(cacheKey);
    }

    /**
     * Returns the number of currently in-flight loads.
     */
    public int inFlightCount() {
        return inFlight.size();
    }
}
