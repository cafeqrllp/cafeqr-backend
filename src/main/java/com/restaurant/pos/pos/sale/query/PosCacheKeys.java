package com.restaurant.pos.pos.sale.query;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Centralized, type-safe cache key builder for POS Sales queries.
 * <p>
 * All POS Redis cache keys should be generated through this class to ensure
 * consistent naming, easy key discovery, and reliable invalidation via
 * {@link PosCacheInvalidationService}.
 * </p>
 * <p>
 * Naming convention: {@code pos:<domain>:<clientId>:<orgId>[:<qualifiers>]}
 * </p>
 */
public final class PosCacheKeys {

    private PosCacheKeys() {
        // Utility class — no instantiation
    }

    // ═══════════════════════════════════════════════════════════════════════
    // KEY PREFIXES (used by invalidation service for pattern-based eviction)
    // ═══════════════════════════════════════════════════════════════════════

    public static final String PREFIX_CONFIG = "pos:config:";
    public static final String PREFIX_SALE_CONFIG = "pos:sale:config:";
    public static final String PREFIX_CATEGORIES = "pos:categories:";
    public static final String PREFIX_PAYMENT_MODES = "pos:payment-modes:";
    public static final String PREFIX_INITIAL_PRODUCTS = "pos:initial-products:";
    public static final String PREFIX_TABLES = "pos:tables:";
    public static final String PREFIX_PRODUCT_SEARCH = "pos:search:products:";
    public static final String PREFIX_CUSTOMER_SEARCH = "pos:search:customers:";
    public static final String PREFIX_PRODUCT_PAGE = "pos:products:page:";
    public static final String PREFIX_CUSTOMER_QUICK = "pos:customers:quick:";

    // ═══════════════════════════════════════════════════════════════════════
    // BASE TTL CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════

    /** Configuration TTL — short because config changes should propagate quickly. */
    public static final Duration TTL_CONFIGURATION = Duration.ofSeconds(30);

    /** Sale screen config TTL — same as configuration. */
    public static final Duration TTL_SALE_CONFIG = Duration.ofSeconds(30);

    /** Categories TTL — relatively stable master data. */
    public static final Duration TTL_CATEGORIES = Duration.ofMinutes(15);

    /** Payment modes TTL — relatively stable master data. */
    public static final Duration TTL_PAYMENT_MODES = Duration.ofMinutes(15);

    /** Initial products TTL — product catalog updates are infrequent. */
    public static final Duration TTL_INITIAL_PRODUCTS = Duration.ofMinutes(5);

    /** Tables TTL — table layout changes are infrequent. */
    public static final Duration TTL_TABLES = Duration.ofMinutes(15);

    /** Product search TTL — search results are ephemeral. */
    public static final Duration TTL_PRODUCT_SEARCH = Duration.ofMinutes(5);

    /** Customer search TTL — search results are ephemeral. */
    public static final Duration TTL_CUSTOMER_SEARCH = Duration.ofMinutes(5);

    /** Product page TTL — paginated results are ephemeral. */
    public static final Duration TTL_PRODUCT_PAGE = Duration.ofMinutes(5);

    /** Customer quick search TTL — search results are ephemeral. */
    public static final Duration TTL_CUSTOMER_QUICK = Duration.ofMinutes(5);

    // ═══════════════════════════════════════════════════════════════════════
    // VERSIONED KEY BUILDERS (embeds namespace version to eliminate race conditions)
    // ═══════════════════════════════════════════════════════════════════════

    public static String configuration(UUID clientId, UUID orgId, long version) {
        return PREFIX_CONFIG + clientId + ":" + orgId + ":v" + version;
    }

    public static String configuration(UUID clientId, UUID orgId) {
        return configuration(clientId, orgId, 1L);
    }

    public static String saleConfig(UUID clientId, UUID orgId, long version) {
        return PREFIX_SALE_CONFIG + clientId + ":" + orgId + ":v" + version;
    }

    public static String saleConfig(UUID clientId, UUID orgId) {
        return saleConfig(clientId, orgId, 1L);
    }

    public static String categories(UUID clientId, UUID orgId, long version) {
        return PREFIX_CATEGORIES + clientId + ":" + orgId + ":v" + version;
    }

    public static String categories(UUID clientId, UUID orgId) {
        return categories(clientId, orgId, 1L);
    }

    public static String paymentModes(UUID clientId, UUID orgId, long version) {
        return PREFIX_PAYMENT_MODES + clientId + ":" + orgId + ":v" + version;
    }

    public static String paymentModes(UUID clientId, UUID orgId) {
        return paymentModes(clientId, orgId, 1L);
    }

    public static String initialProducts(UUID clientId, UUID orgId, long version) {
        return PREFIX_INITIAL_PRODUCTS + clientId + ":" + orgId + ":v" + version;
    }

    public static String initialProducts(UUID clientId, UUID orgId) {
        return initialProducts(clientId, orgId, 1L);
    }

    public static String tables(UUID clientId, UUID orgId, long version) {
        return PREFIX_TABLES + clientId + ":" + orgId + ":v" + version;
    }

    public static String tables(UUID clientId, UUID orgId) {
        return tables(clientId, orgId, 1L);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PARAMETERIZED KEY BUILDERS (versioned)
    // ═══════════════════════════════════════════════════════════════════════

    public static String productSearch(UUID clientId, UUID orgId, long version, UUID categoryId, String search, int limit) {
        return PREFIX_PRODUCT_SEARCH + clientId + ":" + orgId + ":v" + version + ":"
                + (categoryId != null ? categoryId : "all") + ":"
                + (search != null ? search.toLowerCase() : "all") + ":"
                + limit;
    }

    public static String productSearch(UUID clientId, UUID orgId, UUID categoryId, String search, int limit) {
        return productSearch(clientId, orgId, 1L, categoryId, search, limit);
    }

    public static String customerSearch(UUID clientId, UUID orgId, long version, boolean creditOnly, String search, int limit) {
        return PREFIX_CUSTOMER_SEARCH + clientId + ":" + orgId + ":v" + version + ":"
                + (creditOnly ? "credit" : "all") + ":"
                + (search != null ? search.toLowerCase() : "all") + ":"
                + limit;
    }

    public static String customerSearch(UUID clientId, UUID orgId, boolean creditOnly, String search, int limit) {
        return customerSearch(clientId, orgId, 1L, creditOnly, search, limit);
    }

    public static String productPage(UUID clientId, UUID orgId, long version, UUID categoryId, String search, int limit, String cursor) {
        return PREFIX_PRODUCT_PAGE + clientId + ":" + orgId + ":v" + version + ":"
                + (categoryId != null ? categoryId : "all") + ":"
                + (search != null ? search.toLowerCase() : "all") + ":"
                + limit + ":"
                + (cursor != null ? cursor : "first");
    }

    public static String productPage(UUID clientId, UUID orgId, UUID categoryId, String search, int limit, String cursor) {
        return productPage(clientId, orgId, 1L, categoryId, search, limit, cursor);
    }

    public static String customerQuickSearch(UUID clientId, UUID orgId, long version, String search, int limit) {
        return PREFIX_CUSTOMER_QUICK + clientId + ":" + orgId + ":v" + version + ":"
                + (search != null ? search.toLowerCase() : "all") + ":"
                + limit;
    }

    public static String customerQuickSearch(UUID clientId, UUID orgId, String search, int limit) {
        return customerQuickSearch(clientId, orgId, 1L, search, limit);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // JITTERED TTL — prevents synchronized cache expiry across POS terminals
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Applies ±10% random jitter to a base TTL duration to prevent cache stampede.
     * <p>
     * Example: base = 5 minutes → actual = 4m30s to 5m30s
     * </p>
     * <p>
     * This ensures that even if 100 POS terminals cache the same key at roughly
     * the same time, their TTLs expire at slightly different times, spreading
     * the database load across a window rather than creating a single spike.
     * </p>
     *
     * @param base the base TTL duration
     * @return a jittered TTL within ±10% of the base
     */
    public static Duration jittered(Duration base) {
        long baseMs = base.toMillis();
        long jitterRange = baseMs / 10; // 10% of base
        if (jitterRange <= 0) {
            return base;
        }
        long jitter = ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
        return Duration.ofMillis(Math.max(1000, baseMs + jitter)); // minimum 1 second
    }
}
