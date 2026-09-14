package com.restaurant.pos.cache.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Non-blocking cursor-based SCAN service for Redis.
 * <p>
 * Avoids blocking single-threaded Redis engines with dangerous {@code KEYS *} commands
 * by using chunked {@code SCAN} cursors with batch deletions.
 * </p>
 */
@Slf4j
@Service
public class RedisScanService {

    private final StringRedisTemplate redisTemplate;
    private final RedisCircuitBreaker circuitBreaker;

    public RedisScanService(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            RedisCircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Safely deletes all keys matching the given prefix using non-blocking SCAN iteration.
     *
     * @param prefix the key prefix (e.g. "pos:sale:config:")
     * @return the number of deleted keys, or 0 if Redis is inactive or unavailable
     */
    public long deleteByPrefix(String prefix) {
        if (redisTemplate == null || !circuitBreaker.isAvailable() || prefix == null || prefix.isBlank()) {
            return 0L;
        }

        try {
            Long total = redisTemplate.execute((RedisCallback<Long>) connection -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(prefix + "*")
                        .count(100)
                        .build();

                long deletedCount = 0;
                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    List<byte[]> batch = new ArrayList<>(100);
                    while (cursor.hasNext()) {
                        batch.add(cursor.next());
                        if (batch.size() >= 100) {
                            Long deleted = connection.del(batch.toArray(new byte[0][]));
                            if (deleted != null) {
                                deletedCount += deleted;
                            }
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        Long deleted = connection.del(batch.toArray(new byte[0][]));
                        if (deleted != null) {
                            deletedCount += deleted;
                        }
                    }
                }
                return deletedCount;
            });

            long count = total != null ? total : 0L;
            log.debug("Redis prefix eviction via SCAN: prefix='{}', keysDeleted={}", prefix, count);
            return count;
        } catch (Exception ex) {
            circuitBreaker.trip("SCAN_DELETE", prefix, ex);
            return 0L;
        }
    }

    /**
     * Scans for keys matching the prefix up to a specified limit without blocking Redis.
     * Useful for health diagnostics, telemetry, and debugging.
     */
    public Set<String> scanKeys(String prefix, int limit) {
        if (redisTemplate == null || !circuitBreaker.isAvailable() || prefix == null || prefix.isBlank()) {
            return Collections.emptySet();
        }

        try {
            Set<String> keys = new HashSet<>();
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                ScanOptions options = ScanOptions.scanOptions()
                        .match(prefix + "*")
                        .count(Math.min(limit, 100))
                        .build();

                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext() && keys.size() < limit) {
                        keys.add(new String(cursor.next()));
                    }
                }
                return null;
            });
            return keys;
        } catch (Exception ex) {
            circuitBreaker.trip("SCAN_KEYS", prefix, ex);
            return Collections.emptySet();
        }
    }
}
