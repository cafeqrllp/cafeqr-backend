package com.restaurant.pos.pos.sale.query;

import com.restaurant.pos.cache.redis.RedisCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages versioned cache namespaces for POS Sales data.
 * <p>
 * Bounded memory: only fixed domain namespaces (CONFIG, CATEGORIES, PAYMENT_MODES,
 * PRODUCTS, TABLES, CUSTOMERS) are versioned per tenant/org.
 * </p>
 * <p>
 * When an admin updates data, the domain version is incremented.
 * Subsequent reads immediately query the new versioned key, while old versioned
 * keys naturally expire on TTL. This completely eliminates:
 * <ul>
 *   <li>Cache-write race conditions (old queries write to old version keys)</li>
 *   <li>Expensive Redis prefix SCAN and bulk DEL operations</li>
 *   <li>Unbounded memory growth in version tracking maps</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class PosCacheVersionService {

    public enum Namespace {
        CONFIG("config"),
        CATEGORIES("categories"),
        PAYMENT_MODES("payment-modes"),
        PRODUCTS("products"),
        TABLES("tables"),
        CUSTOMERS("customers");

        private final String code;

        Namespace(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final RedisCacheService redisCacheService;
    // In-memory fast-cache for versions to avoid Redis GET round-trip on every read
    private final Map<String, AtomicLong> localVersions = new ConcurrentHashMap<>();

    public PosCacheVersionService(RedisCacheService redisCacheService) {
        this.redisCacheService = redisCacheService;
    }

    private String buildVersionKey(Namespace namespace, UUID clientId, UUID orgId) {
        return "pos:ns-version:" + namespace.getCode() + ":" + clientId + ":" + orgId;
    }

    private String buildLocalKey(Namespace namespace, UUID clientId, UUID orgId) {
        return namespace.getCode() + ":" + clientId + ":" + orgId;
    }

    /**
     * Retrieves the current version for the given namespace and tenant/org.
     * Uses Redis as the single distributed source of truth across all application instances.
     * Falls back to local in-memory tracking only if Redis is unavailable.
     */
    public long getVersion(Namespace namespace, UUID clientId, UUID orgId) {
        String versionKey = buildVersionKey(namespace, clientId, orgId);
        if (redisCacheService != null && redisCacheService.isAvailable()) {
            Optional<String> redisVal = redisCacheService.getString(versionKey);
            if (redisVal.isPresent() && !redisVal.get().isBlank()) {
                try {
                    return Long.parseLong(redisVal.get());
                } catch (NumberFormatException ignored) {
                }
            }
            return 1L;
        }

        // Local in-memory fallback only when Redis is unavailable
        String localKey = buildLocalKey(namespace, clientId, orgId);
        return localVersions.computeIfAbsent(localKey, k -> new AtomicLong(1L)).get();
    }

    /**
     * Increments the version for the given namespace and tenant/org in Redis.
     * Atomically invalidates all cache entries under this namespace across all instances.
     */
    public long incrementVersion(Namespace namespace, UUID clientId, UUID orgId) {
        String versionKey = buildVersionKey(namespace, clientId, orgId);
        if (redisCacheService != null && redisCacheService.isAvailable()) {
            Long val = redisCacheService.increment(versionKey);
            if (val != null) {
                log.info("POS cache namespace version incremented in Redis: {} -> v{}", versionKey, val);
                return val;
            }
        }

        // Local in-memory fallback only when Redis is unavailable
        String localKey = buildLocalKey(namespace, clientId, orgId);
        long newVersion = localVersions.computeIfAbsent(localKey, k -> new AtomicLong(1L)).incrementAndGet();
        log.info("POS cache namespace version incremented (local fallback): {} -> v{}", localKey, newVersion);
        return newVersion;
    }
}
