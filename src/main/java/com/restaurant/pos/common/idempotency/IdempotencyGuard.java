package com.restaurant.pos.common.idempotency;

import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.idempotency.OrderIdempotencyStore;
import com.restaurant.pos.order.exception.ConcurrentIdempotentRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final OrderIdempotencyStore orderIdempotencyStore;

    /**
     * Executes a supplier action with dynamic, tenant-scoped idempotency controls.
     * Incorporates atomic distributed locking to prevent duplicate concurrent request race conditions.
     * Generically typed to avoid any domain dependencies (e.g. Order DTOs).
     */
    public <T> T execute(
            String action,
            UUID resourceId,
            String idempotencyKey,
            Class<T> responseClass,
            Supplier<T> actionSupplier) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            UUID tenantId = TenantContext.getCurrentTenant();
            String cacheKey = String.format("tenant=%s:resource=%s:action=%s:key=%s",
                    tenantId != null ? tenantId : "system", resourceId, action, idempotencyKey);
            
            // 1. Check if a completed response is already cached
            T cached = orderIdempotencyStore.get(cacheKey, responseClass);
            if (cached != null) {
                log.info("Idempotency hit | action={} | resourceId={} | key={}", action, resourceId, idempotencyKey);
                return cached;
            }

            // 2. Try to acquire the in-flight lock (SET NX PX)
            boolean locked = orderIdempotencyStore.acquireLock(cacheKey, Duration.ofSeconds(15));
            if (!locked) {
                log.warn("Concurrent request blocked (lock active) | action={} | resourceId={} | key={}", action, resourceId, idempotencyKey);
                throw new ConcurrentIdempotentRequestException("A request with the same idempotency key is already in progress.");
            }

            try {
                // 3. Double-check cache in case concurrent execution completed in the microsecond window
                cached = orderIdempotencyStore.get(cacheKey, responseClass);
                if (cached != null) {
                    return cached;
                }

                T result = actionSupplier.get();
                orderIdempotencyStore.put(cacheKey, result);
                return result;
            } finally {
                // 4. Always release the lock when finished
                orderIdempotencyStore.releaseLock(cacheKey);
            }
        }
        return actionSupplier.get();
    }
}
