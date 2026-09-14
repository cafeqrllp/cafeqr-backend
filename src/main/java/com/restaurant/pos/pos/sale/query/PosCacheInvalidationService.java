package com.restaurant.pos.pos.sale.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Pure version-increment cache invalidation service for POS Sales data.
 * <p>
 * Instead of performing expensive and race-prone key evictions or prefix SCAN/DEL operations,
 * this service simply increments the namespace version counter in {@link PosCacheVersionService}.
 * Subsequent reads immediately query new versioned keys (e.g. {@code :v43}), while previous
 * version entries naturally expire on their TTL.
 * </p>
 */
@Slf4j
@Service
public class PosCacheInvalidationService {

    private final PosCacheVersionService versionService;

    public PosCacheInvalidationService(PosCacheVersionService versionService) {
        this.versionService = versionService;
    }

    /**
     * Invalidates POS configuration cache by incrementing CONFIG namespace version.
     */
    public void invalidateConfiguration(UUID clientId, UUID orgId) {
        long v = versionService.incrementVersion(PosCacheVersionService.Namespace.CONFIG, clientId, orgId);
        log.info("POS cache invalidated: CONFIG namespace -> v{} | clientId={}, orgId={}", v, clientId, orgId);
    }

    /**
     * Invalidates category & product caches by incrementing CATEGORIES and PRODUCTS versions.
     */
    public void invalidateCategories(UUID clientId, UUID orgId) {
        long vCat = versionService.incrementVersion(PosCacheVersionService.Namespace.CATEGORIES, clientId, orgId);
        long vProd = versionService.incrementVersion(PosCacheVersionService.Namespace.PRODUCTS, clientId, orgId);
        log.info("POS cache invalidated: CATEGORIES (v{}) & PRODUCTS (v{}) | clientId={}, orgId={}", vCat, vProd, clientId, orgId);
    }

    /**
     * Invalidates payment modes cache by incrementing PAYMENT_MODES version.
     */
    public void invalidatePaymentModes(UUID clientId, UUID orgId) {
        long v = versionService.incrementVersion(PosCacheVersionService.Namespace.PAYMENT_MODES, clientId, orgId);
        log.info("POS cache invalidated: PAYMENT_MODES namespace -> v{} | clientId={}, orgId={}", v, clientId, orgId);
    }

    /**
     * Invalidates all product-related caches by incrementing PRODUCTS version.
     */
    public void invalidateProducts(UUID clientId, UUID orgId) {
        long v = versionService.incrementVersion(PosCacheVersionService.Namespace.PRODUCTS, clientId, orgId);
        log.info("POS cache invalidated: PRODUCTS namespace -> v{} | clientId={}, orgId={}", v, clientId, orgId);
    }

    /**
     * Invalidates table layout cache by incrementing TABLES version.
     */
    public void invalidateTables(UUID clientId, UUID orgId) {
        long v = versionService.incrementVersion(PosCacheVersionService.Namespace.TABLES, clientId, orgId);
        log.info("POS cache invalidated: TABLES namespace -> v{} | clientId={}, orgId={}", v, clientId, orgId);
    }

    /**
     * Invalidates all POS caches by incrementing all namespace versions.
     */
    public void invalidateAll(UUID clientId, UUID orgId) {
        for (PosCacheVersionService.Namespace ns : PosCacheVersionService.Namespace.values()) {
            versionService.incrementVersion(ns, clientId, orgId);
        }
        log.info("POS cache invalidated: ALL namespaces incremented | clientId={}, orgId={}", clientId, orgId);
    }
}
