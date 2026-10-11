package com.restaurant.pos.qrmenu.query;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, in-memory cache for the public QR menu. The menu is unauthenticated and was rebuilt
 * from the database (products + config + stock) on every request. Entries are dropped immediately
 * when products, stock, tables or orders of the branch change (see {@code RealtimeChangeService}),
 * so the TTL is only a safety net. Concurrent requests for a cold key share a single load.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QrMenuCacheService {

    private static final long TTL_MS = 30_000L;
    private static final int MAX_ENTRIES = 2_000;
    private static final UUID NO_ORG = new UUID(0L, 0L);

    private record Key(UUID clientId, UUID orgId) {}

    private static final class Entry {
        final long loadedAt = System.currentTimeMillis();
        final List<Map<String, Object>> menu;

        Entry(List<Map<String, Object>> menu) {
            this.menu = menu;
        }

        boolean expired() {
            return System.currentTimeMillis() - loadedAt > TTL_MS;
        }
    }

    private final QrOrderQueryService queryService;
    private final PlatformTransactionManager transactionManager;
    private final ConcurrentHashMap<Key, Entry> cache = new ConcurrentHashMap<>();

    public List<Map<String, Object>> getMenu(UUID clientId, UUID orgId) {
        Key key = new Key(clientId, orgId != null ? orgId : NO_ORG);
        Entry hit = cache.get(key);
        if (hit != null && !hit.expired()) {
            return hit.menu;
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        // compute() runs the loader once per key; other callers for the same key wait for it.
        // Invalidation during a load removes the entry afterwards, so a stale result is never kept.
        return cache.compute(key, (k, existing) -> {
            if (existing != null && !existing.expired()) {
                return existing;
            }
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setReadOnly(true);
            List<Map<String, Object>> menu = tx.execute(status -> queryService.getMenu(clientId, orgId));
            return new Entry(menu);
        }).menu;
    }

    /** Drops cached menus affected by a change. A change with no branch affects every branch of the tenant. */
    public void invalidate(UUID clientId, UUID orgId) {
        if (clientId == null) {
            return;
        }
        cache.keySet().removeIf(k -> k.clientId().equals(clientId)
                && (orgId == null || k.orgId().equals(orgId) || k.orgId().equals(NO_ORG)));
    }
}
