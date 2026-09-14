package com.restaurant.pos.pos.sale.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.paymenttype.domain.PaymentType;
import com.restaurant.pos.paymenttype.query.PaymentTypeQueryService;
import com.restaurant.pos.pos.sale.dto.*;
import com.restaurant.pos.product.domain.Category;
import com.restaurant.pos.product.service.ProductService;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.service.RestaurantTableService;
import com.restaurant.pos.common.tenant.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.restaurant.pos.cache.redis.RedisCacheService;
import com.restaurant.pos.cache.loader.SingleFlightCacheLoader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * POS Sale Query Service — the "Read Side" of in-process CQRS for POS sales.
 * <p>
 * This service handles all read operations for POS Sales with resilient Redis
 * cache-aside:
 * <ul>
 * <li>Single-call Sales Screen Details with Redis cache + fallback</li>
 * <li>Product catalog and on-demand search with Redis cache + fallback</li>
 * <li>Customer quick search with Redis cache + fallback</li>
 * <li>Automatic graceful fallback to DB if Redis is absent or inactive</li>
 * <li>Live (open) order listing and high-speed sales history</li>
 * </ul>
 */
@Slf4j
@Service
public class PosSaleQueryService {

    public record TenantOrgContext(UUID clientId, UUID orgId) {
    }

    private static final int MAX_HISTORY_PAGE_SIZE = 200;
    private static final Duration DEFAULT_HISTORY_WINDOW = Duration.ofDays(1);
    private static final Duration MAX_HISTORY_WINDOW = Duration.ofDays(3650);

    private final PosSaleProjectionRepository projectionRepository;
    private final SystemConfigurationService configurationService;
    private final ProductService productService;
    private final PaymentTypeQueryService paymentTypeQueryService;
    private final RestaurantTableService restaurantTableService;
    private final BranchContextService branchContext;
    private final RedisCacheService redisCacheService;
    private final ExecutorService salesScreenExecutor;
    private final SingleFlightCacheLoader singleFlightLoader;
    private final PosCacheVersionService versionService;

    public PosSaleQueryService(
            PosSaleProjectionRepository projectionRepository,
            SystemConfigurationService configurationService,
            ProductService productService,
            PaymentTypeQueryService paymentTypeQueryService,
            RestaurantTableService restaurantTableService,
            BranchContextService branchContext,
            RedisCacheService redisCacheService,
            @Autowired(required = false) @Qualifier("posSalesScreenExecutor") ExecutorService salesScreenExecutor,
            SingleFlightCacheLoader singleFlightLoader,
            PosCacheVersionService versionService) {
        this.projectionRepository = projectionRepository;
        this.configurationService = configurationService;
        this.productService = productService;
        this.paymentTypeQueryService = paymentTypeQueryService;
        this.restaurantTableService = restaurantTableService;
        this.branchContext = branchContext;
        this.redisCacheService = redisCacheService;
        this.salesScreenExecutor = salesScreenExecutor;
        this.singleFlightLoader = singleFlightLoader;
        this.versionService = versionService;
    }

    private ExecutorService getExecutor() {
        return salesScreenExecutor;
    }

    private <T> java.util.function.Supplier<T> withUserContext(UserContext.UserContextData contextData, java.util.function.Supplier<T> supplier) {
        return () -> {
            UserContext.setContext(contextData);
            try {
                return supplier.get();
            } finally {
                UserContext.clear();
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESILIENT REDIS CACHE ENGINE (Delegated to RedisCacheService)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Checks if Redis is present and active (not in circuit-breaker backoff).
     */
    public boolean isRedisActive() {
        return redisCacheService != null && redisCacheService.isAvailable();
    }

    /**
     * Centralized, uniform tenant and organization resolution across all POS read
     * operations.
     */
    public TenantOrgContext resolveTenantOrgContext(UUID overrideOrgId) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId;
        if (SecurityUtils.isSuperAdmin()) {
            orgId = overrideOrgId != null ? overrideOrgId : branchContext.getReadOrgId(null);
        } else {
            orgId = TenantContext.getCurrentOrg();
        }
        return new TenantOrgContext(clientId, orgId);
    }

    /**
     * Returns the full sales-related configuration for the current branch/tenant.
     * Uses Redis cache-aside with graceful DB fallback.
     */
    @Transactional(readOnly = true)
    public ConfigurationDto getConfigurations() {
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        long version = versionService.getVersion(PosCacheVersionService.Namespace.CONFIG, ctx.clientId(), ctx.orgId());
        String cacheKey = PosCacheKeys.configuration(ctx.clientId(), ctx.orgId(), version);

        Optional<ConfigurationDto> cached = redisCacheService.get(cacheKey, ConfigurationDto.class);
        if (cached.isPresent()) {
            log.debug("POS configurations returned from Redis cache | key={}", cacheKey);
            return cached.get();
        }

        return singleFlightLoader.loadAndCache(cacheKey,
                () -> configurationService.getConfiguration(),
                (k, v) -> redisCacheService.put(k, v, PosCacheKeys.jittered(PosCacheKeys.TTL_CONFIGURATION)));
    }


    /**
     * Single-call POS Sales Screen Details aggregating configuration, categories,
     * payment modes, conditional products (STANDARD mode only), and conditional tables.
     * Uses Redis cache-aside with automatic graceful fallback to DB.
     * Transaction boundaries are owned by individual services to support true parallel concurrency.
     */
    public SalesScreenDetails  getSalesScreenDetails() throws Exception {
        final long t0 = System.nanoTime();
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        final UUID clientId = ctx.clientId();
        final UUID orgId = ctx.orgId();

        // 1. Load configuration (Redis cache-aside with DB fallback)
        final long tConfigStart = System.nanoTime();
        long configNsVersion = versionService.getVersion(PosCacheVersionService.Namespace.CONFIG, clientId, orgId);
        String configCacheKey = PosCacheKeys.saleConfig(clientId, orgId, configNsVersion);
        SalesScreenConfiguration config = redisCacheService.get(configCacheKey, SalesScreenConfiguration.class)
                .orElseGet(() -> singleFlightLoader.loadAndCache(configCacheKey, () -> {
                    ConfigurationDto rawConfig = configurationService.getConfiguration();
                    return mapSalesScreenConfiguration(rawConfig);
                }, (k, v) -> redisCacheService.put(k, v, PosCacheKeys.jittered(PosCacheKeys.TTL_SALE_CONFIG))));
        final long configTimeMs = elapsedMs(tConfigStart);

        // Feature & Requirement decision layer
        final SalesScreenRequirements req = SalesScreenRequirements.from(config);

        // Capture UserContext for async worker threads
        final UserContext.UserContextData callerContext = UserContext.getContext();
        final ExecutorService executor = getExecutor();

        // Per-query actual execution timing accumulators
        final AtomicLong categoriesTimeMs = new AtomicLong();
        final AtomicLong paymentModesTimeMs = new AtomicLong();
        final AtomicLong productsTimeMs = new AtomicLong();
        final AtomicLong tablesTimeMs = new AtomicLong();

        // 2. Parallelize independent queries with context propagation & accurate per-query latency tracking
        CompletableFuture<List<ProductCategoryBean>> categoriesFuture = CompletableFuture.supplyAsync(
                withUserContext(callerContext, () -> {
                    long s = System.nanoTime();
                    try {
                        return getCategoryBeans(clientId, orgId);
                    } finally {
                        categoriesTimeMs.set(elapsedMs(s));
                    }
                }), executor);

        CompletableFuture<List<PaymentModeBean>> paymentModesFuture = CompletableFuture.supplyAsync(
                withUserContext(callerContext, () -> {
                    long s = System.nanoTime();
                    try {
                        return getPaymentModeBeans(clientId, orgId);
                    } finally {
                        paymentModesTimeMs.set(elapsedMs(s));
                    }
                }), executor);

        CompletableFuture<List<ProductBean>> productsFuture = req.isStandardMode()
                ? CompletableFuture.supplyAsync(
                        withUserContext(callerContext, () -> {
                            long s = System.nanoTime();
                            try {
                                return getInitialProductBeans(clientId, orgId);
                            } finally {
                                productsTimeMs.set(elapsedMs(s));
                            }
                        }), executor)
                : CompletableFuture.completedFuture(Collections.emptyList());

        CompletableFuture<List<TableBean>> tablesFuture = req.isTableEnabled()
                ? CompletableFuture.supplyAsync(
                        withUserContext(callerContext, () -> {
                            long s = System.nanoTime();
                            try {
                                return getTableBeans(clientId, orgId);
                            } finally {
                                tablesTimeMs.set(elapsedMs(s));
                            }
                        }), executor)
                : CompletableFuture.completedFuture(Collections.emptyList());

        // 3. Wait for all independent tasks
        CompletableFuture.allOf(categoriesFuture, paymentModesFuture, productsFuture, tablesFuture).join();

        List<ProductCategoryBean> categories = categoriesFuture.get();
        List<PaymentModeBean> paymentModes = paymentModesFuture.get();
        List<ProductBean> products = productsFuture.get();
        List<TableBean> tables = tablesFuture.get();

        final long totalTimeMs = elapsedMs(t0);
        log.info(
                "SalesScreenDetails completed: total={}ms [config={}ms, categories={}ms({}), products={}ms({}), tables={}ms({}), paymentModes={}ms({})]",
                totalTimeMs, configTimeMs,
                categoriesTimeMs.get(), categories.size(),
                productsTimeMs.get(), products.size(),
                tablesTimeMs.get(), tables.size(),
                paymentModesTimeMs.get(), paymentModes.size());

        return SalesScreenDetails.builder()
                .serverTimestamp(Instant.now())
                .configurationVersion(config.getConfigurationVersion())
                .configuration(config)
                .categories(categories)
                .products(products)
                .tables(tables)
                .paymentModes(paymentModes)
                .build();
    }

    /**
     * Lazy product search / category filter for both Counter search and Standard
     * browsing.
     * Uses Redis cache-aside with graceful DB fallback.
     */
    @Transactional(readOnly = true)
    public List<ProductBean> searchScreenProducts(UUID categoryId, String search, int requestedLimit) {
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        String normSearch = normalizeSearch(search);

        long version = versionService.getVersion(PosCacheVersionService.Namespace.PRODUCTS, ctx.clientId(), ctx.orgId());
        String cacheKey = PosCacheKeys.productSearch(ctx.clientId(), ctx.orgId(), version, categoryId, normSearch, limit);

        Optional<List<ProductBean>> cached = redisCacheService.get(cacheKey, new TypeReference<List<ProductBean>>() {
        });
        if (cached.isPresent()) {
            log.debug("Screen products search hit in Redis | key={}", cacheKey);
            return cached.get();
        }

        List<PosProductSummaryView> views = projectionRepository.findProductsKeyset(
                ctx.clientId(), ctx.orgId(), categoryId, normSearch, null, null, limit);

        List<ProductBean> result = views.stream().map(this::mapProductBean).toList();
        redisCacheService.put(cacheKey, result, PosCacheKeys.jittered(PosCacheKeys.TTL_PRODUCT_SEARCH));
        return result;
    }

    /**
     * Dedicated customer search API supporting regular and credit-only lookups.
     * Uses Redis cache-aside with graceful DB fallback.
     */
    @Transactional(readOnly = true)
    public List<CustomerSearchBean> searchCustomers(String search, boolean creditOnly, int requestedLimit) {
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        int limit = Math.min(Math.max(requestedLimit, 1), 50);
        String normSearch = normalizeSearch(search);

        long version = versionService.getVersion(PosCacheVersionService.Namespace.CUSTOMERS, ctx.clientId(), ctx.orgId());
        String cacheKey = PosCacheKeys.customerSearch(ctx.clientId(), ctx.orgId(), version, creditOnly, normSearch, limit);

        Optional<List<CustomerSearchBean>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<CustomerSearchBean>>() {
                });
        if (cached.isPresent()) {
            log.debug("Customer search hit in Redis | key={}", cacheKey);
            return cached.get();
        }

        List<CustomerSearchBean> result;
        if (creditOnly) {
            List<PosCustomerSummaryView> views = projectionRepository.findCreditCustomersQuickSearch(
                    ctx.clientId(), normSearch, limit);
            result = views.stream().map(v -> CustomerSearchBean.builder()
                    .id(v.getId())
                    .name(v.getName())
                    .phone(v.getPhone())
                    .email(v.getEmail())
                    .isCreditCustomer(true)
                    .creditLimit(v.getCreditLimit())
                    .balance(v.getBalance() != null ? v.getBalance() : BigDecimal.ZERO)
                    .loyaltyPoints(0)
                    .build()).toList();
        } else {
            List<PosCustomerSummaryView> views = projectionRepository.findCustomersQuickSearch(
                    ctx.clientId(), ctx.orgId(), normSearch, limit);
            result = views.stream().map(v -> CustomerSearchBean.builder()
                    .id(v.getId())
                    .name(v.getName())
                    .phone(v.getPhone())
                    .email(v.getEmail())
                    .isCreditCustomer(v.getCreditLimit() != null && v.getCreditLimit().signum() > 0)
                    .creditLimit(v.getCreditLimit())
                    .balance(v.getBalance() != null ? v.getBalance() : BigDecimal.ZERO)
                    .loyaltyPoints(v.getLoyaltyPoints())
                    .build()).toList();
        }

        redisCacheService.put(cacheKey, result, PosCacheKeys.jittered(PosCacheKeys.TTL_CUSTOMER_SEARCH));
        return result;
    }

    private List<ProductCategoryBean> getCategoryBeans(UUID clientId, UUID orgId) {
        long version = versionService.getVersion(PosCacheVersionService.Namespace.CATEGORIES, clientId, orgId);
        String cacheKey = PosCacheKeys.categories(clientId, orgId, version);
        Optional<List<ProductCategoryBean>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<ProductCategoryBean>>() {
                });
        if (cached.isPresent()) {
            return cached.get();
        }

        return singleFlightLoader.loadAndCache(cacheKey, () -> {
            // Critical metadata - fail fast so the POS screen never mistakenly renders an empty catalog
            List<Category> list = productService.getCategories();
            if (list == null)
                return Collections.<ProductCategoryBean>emptyList();
            return list.stream()
                    .filter(c -> c != null && c.isActive())
                    .map(c -> ProductCategoryBean.builder()
                            .id(c.getId() != null ? c.getId().toString() : null)
                            .name(c.getName())
                            .build())
                    .toList();
        }, (k, v) -> redisCacheService.put(k, v, PosCacheKeys.jittered(PosCacheKeys.TTL_CATEGORIES)));
    }

    public UUID findCategoryIdByName(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            return null;
        }
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        List<ProductCategoryBean> categories = getCategoryBeans(ctx.clientId(), ctx.orgId());
        return categories.stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(categoryName.trim()))
                .map(c -> {
                    try {
                        return c.getId() != null ? UUID.fromString(c.getId()) : null;
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<PaymentModeBean> getPaymentModeBeans(UUID clientId, UUID orgId) {
        long version = versionService.getVersion(PosCacheVersionService.Namespace.PAYMENT_MODES, clientId, orgId);
        String cacheKey = PosCacheKeys.paymentModes(clientId, orgId, version);
        Optional<List<PaymentModeBean>> cached = redisCacheService.get(cacheKey, new TypeReference<List<PaymentModeBean>>() {
        });
        if (cached.isPresent()) {
            return cached.get();
        }

        return singleFlightLoader.loadAndCache(cacheKey, () -> {
            // Critical metadata - fail fast
            List<PaymentType> list = paymentTypeQueryService.getPaymentTypesByApplicableFor("SALES", orgId);
            if (list == null)
                return Collections.<PaymentModeBean>emptyList();
            return list.stream()
                    .map(pt -> PaymentModeBean.builder()
                            .id(pt.getId())
                            .displayName(pt.getDisplayName())
                            .paymentType(pt.getPaymentType())
                            .isDefault(Boolean.TRUE.equals(pt.getIsDefault()))
                            .sortOrder(pt.getSortOrder())
                            .build())
                    .toList();
        }, (k, v) -> redisCacheService.put(k, v, PosCacheKeys.jittered(PosCacheKeys.TTL_PAYMENT_MODES)));
    }

    private List<ProductBean> getInitialProductBeans(UUID clientId, UUID orgId) {
        long version = versionService.getVersion(PosCacheVersionService.Namespace.PRODUCTS, clientId, orgId);
        String cacheKey = PosCacheKeys.initialProducts(clientId, orgId, version);
        Optional<List<ProductBean>> cached = redisCacheService.get(cacheKey, new TypeReference<List<ProductBean>>() {
        });
        if (cached.isPresent()) {
            return cached.get();
        }

        return singleFlightLoader.loadAndCache(cacheKey, () -> {
            // Critical metadata - fail fast
            List<PosProductSummaryView> list = projectionRepository.findProductsKeyset(
                    clientId, orgId, null, null, null, null, 50);
            return list.stream().map(this::mapProductBean).toList();
        }, (k, v) -> redisCacheService.put(k, v, PosCacheKeys.jittered(PosCacheKeys.TTL_INITIAL_PRODUCTS)));
    }

    private List<TableBean> getTableBeans(UUID clientId, UUID orgId) {
        long version = versionService.getVersion(PosCacheVersionService.Namespace.TABLES, clientId, orgId);
        String cacheKey = PosCacheKeys.tables(clientId, orgId, version);
        Optional<List<TableBean>> cached = redisCacheService.get(cacheKey, new TypeReference<List<TableBean>>() {
        });
        if (cached.isPresent()) {
            return cached.get();
        }

        try {
            return singleFlightLoader.loadAndCache(cacheKey, () -> {
                List<RestaurantTable> tables = restaurantTableService.getActiveTables();
                if (tables == null)
                    return Collections.<TableBean>emptyList();
                return tables.stream().map(t -> TableBean.builder()
                        .id(t.getId())
                        .tableNumber(t.getTableNumber())
                        .name(t.getName())
                        .seatingCapacity(t.getSeatingCapacity())
                        .floor(t.getFloor())
                        .section(t.getSection())
                        .shape(t.getShape())
                        .status(t.getStatus())
                        .displayOrder(t.getDisplayOrder())
                        .build()).toList();
            }, (k, v) -> redisCacheService.put(k, v, PosCacheKeys.jittered(PosCacheKeys.TTL_TABLES)));
        } catch (Exception ex) {
            // Tables are conditional/optional - gracefully degrade
            log.warn("Failed to load tables for sales screen (conditional component)", ex);
            return Collections.emptyList();
        }
    }

    private ProductBean mapProductBean(PosProductSummaryView p) {
        return ProductBean.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .costPrice(p.getCostPrice())
                .mrp(p.getMrp())
                .isAvailable(p.getIsAvailable())
                .imageUrl(p.getImageUrl())
                .categoryId(p.getCategoryId())
                .categoryName(p.getCategoryName())
                .productCode(p.getProductCode())
                .barcode(p.getBarcode())
                .productType(p.getProductType())
                .taxRate(p.getTaxRate())
                .taxCode(p.getTaxCode())
                .isPackagedGood(p.getIsPackagedGood())
                .isVariablePrice(p.getIsVariablePrice())
                .isVariant(p.getIsVariant())
                .build();
    }

    private SalesScreenConfiguration mapSalesScreenConfiguration(ConfigurationDto c) {
        if (c == null) {
            return SalesScreenConfiguration.builder().build();
        }
        String billingMode = c.getDefaultBillingUiMode();
        String salesType = "counter".equalsIgnoreCase(billingMode) ? "COUNTER" : "STANDARD";
        boolean productListing = !"counter".equalsIgnoreCase(billingMode) && c.isPosProductListingEnabled();

        // Derive configurationVersion from actual DB updatedAt timestamp (epoch millis).
        // Deterministic across cache misses and TTL expiries; only changes when admin modifies config.
        Long configVersion = c.getUpdatedAt() != null
                ? c.getUpdatedAt().atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                : 0L;

        return SalesScreenConfiguration.builder()
                .salesType(salesType)
                .defaultBillingUiMode(billingMode != null ? billingMode.toLowerCase() : "board")
                .configurationVersion(configVersion)
                .posProductListingEnabled(productListing)
                .tableEnabled(c.isTableManagementEnabled())
                .customerEnabled(c.isCustomersEnabled())
                .discountEnabled(c.isDiscountEnabled())
                .barcodeScannerEnabled(c.isBarcodeScannerEnabled())
                .onlineDeliveryEnabled(c.isOnlineDeliveryEnabled())
                .takeawayAutoPrintKotOnSettle(c.isTakeawayAutoPrintKotOnSettle())
                .dineInAutoPrintKotOnSettle(c.isDineInAutoPrintKotOnSettle())
                .takeawayHideKitchenMode(c.isTakeawayHideKitchenMode())
                .dineInHideKitchenMode(c.isDineInHideKitchenMode())
                .sendToKitchenEnabled(c.isSendToKitchenEnabled())
                .loyaltyEnabled(c.isLoyaltyEnabled())
                .currencySymbol(c.getCurrencySymbol())
                .currencyPosition(c.getCurrencyPosition())
                .currencyDecimalPlaces(c.getCurrencyDecimalPlaces() != null ? c.getCurrencyDecimalPlaces() : 2)
                .roundOffEnabled(c.isRoundOffEnabled())
                .roundOffMode(c.getRoundOffMode())
                .roundOffAutoFactor(c.getRoundOffAutoFactor())
                .roundOffManualLimit(c.getRoundOffManualLimit())
                .taxEnabled(c.isTaxEnabled())
                .taxLabelGlobal(c.getTaxLabelGlobal())
                .pricesIncludeTax(c.isPricesIncludeTax())
                .taxSplitEnabled(c.isTaxSplitEnabled())
                .taxRates(c.getTaxRates() != null ? c.getTaxRates() : Collections.emptyList())
                .build();
    }

    private long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    /**
     * DB-level fast customer search bounded directly by SQL limit.
     * Uses Redis cache-aside with graceful DB fallback.
     */
    @Transactional(readOnly = true)
    public List<PosCustomerSummaryView> searchCustomers(String search, int requestedLimit) {
        int limit = Math.min(Math.max(requestedLimit, 1), 50);
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        String normalizedSearch = normalizeSearch(search);

        String cacheKey = PosCacheKeys.customerQuickSearch(ctx.clientId(), ctx.orgId(), normalizedSearch, limit);

        Optional<List<PosCustomerSummaryDto>> cached = redisCacheService.get(cacheKey,
                new TypeReference<List<PosCustomerSummaryDto>>() {
                });
        if (cached.isPresent()) {
            log.debug("Quick customer search hit in Redis | key={}", cacheKey);
            return new java.util.ArrayList<>(cached.get());
        }

        List<PosCustomerSummaryView> views = projectionRepository.findCustomersQuickSearch(
                ctx.clientId(), ctx.orgId(), normalizedSearch, limit);

        List<PosCustomerSummaryDto> dtos = views.stream()
                .map(PosCustomerSummaryDto::from)
                .toList();
        redisCacheService.put(cacheKey, dtos, PosCacheKeys.jittered(PosCacheKeys.TTL_CUSTOMER_QUICK));

        return views;
    }

    /**
     * Keyset-paginated product catalog query for POS Standard Mode.
     * Uses Redis cache-aside with graceful DB fallback.
     */
    @Transactional(readOnly = true)
    public com.restaurant.pos.pos.sale.dto.PosProductPageResponse getProducts(
            UUID categoryId,
            String search,
            int requestedLimit,
            String cursor) {

        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        String normalizedSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        String cacheKey = PosCacheKeys.productPage(ctx.clientId(), ctx.orgId(), categoryId, normalizedSearch, limit, cursor);

        Optional<PosProductPageDto> cached = redisCacheService.get(cacheKey, PosProductPageDto.class);
        if (cached.isPresent()) {
            log.debug("Product page hit in Redis | key={}", cacheKey);
            PosProductPageDto dto = cached.get();
            List<PosProductSummaryView> items = dto.getItems() != null
                    ? new java.util.ArrayList<>(dto.getItems())
                    : Collections.emptyList();
            return new com.restaurant.pos.pos.sale.dto.PosProductPageResponse(
                    items,
                    dto.getNextCursor(),
                    dto.isHasMore());
        }

        com.restaurant.pos.pos.sale.dto.ProductCursor decodedCursor = com.restaurant.pos.pos.sale.dto.ProductCursor
                .decode(cursor);

        String cursorName = decodedCursor != null ? decodedCursor.name() : null;
        UUID cursorId = decodedCursor != null ? decodedCursor.id() : null;

        java.util.List<PosProductSummaryView> products = projectionRepository.findProductsKeyset(
                ctx.clientId(),
                ctx.orgId(),
                categoryId,
                normalizedSearch,
                cursorName,
                cursorId,
                limit + 1);

        boolean hasMore = products.size() > limit;
        if (hasMore) {
            products = products.subList(0, limit);
        }

        String nextCursor = null;
        if (hasMore && !products.isEmpty()) {
            PosProductSummaryView last = products.get(products.size() - 1);
            nextCursor = com.restaurant.pos.pos.sale.dto.ProductCursor.of(last).encode();
        }

        List<PosProductSummaryDto> dtoItems = products.stream()
                .map(PosProductSummaryDto::from)
                .toList();

        redisCacheService.put(cacheKey,
                new PosProductPageDto(dtoItems, nextCursor, hasMore),
                PosCacheKeys.jittered(PosCacheKeys.TTL_PRODUCT_PAGE));

        return new com.restaurant.pos.pos.sale.dto.PosProductPageResponse(
                java.util.List.copyOf(products),
                nextCursor,
                hasMore);
    }


    /**
     * Returns a lightweight, zero-hydration sales history slice.
     * Uses native SQL projections with covering indexes — no JPA entity loading,
     * no N+1 customer/order-line hydration, no {@code @Formula} subqueries.
     *
     * @return {@link Slice} to avoid expensive {@code COUNT(*)} queries
     */
    @Transactional(readOnly = true)
    public Slice<PosSaleSummaryView> getSalesHistory(
            Instant fromDate, Instant toDate,
            int page, int size,
            String status, String search,
            UUID paramOrgId, UUID terminalId) {

        Instant effectiveTo = toDate != null ? toDate : Instant.now();
        Instant effectiveFrom = fromDate != null ? fromDate : effectiveTo.minus(DEFAULT_HISTORY_WINDOW);

        // Guard against inverted dates while allowing wide query ranges up to MAX_HISTORY_WINDOW
        if (effectiveFrom.isAfter(effectiveTo)) {
            effectiveFrom = effectiveTo.minus(DEFAULT_HISTORY_WINDOW);
        } else if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_HISTORY_WINDOW) > 0) {
            effectiveFrom = effectiveTo.minus(MAX_HISTORY_WINDOW);
        }

        int clampedSize = Math.max(1, Math.min(size, MAX_HISTORY_PAGE_SIZE));
        int clampedPage = Math.max(0, page);

        TenantOrgContext ctx = resolveTenantOrgContext(paramOrgId);

        String normalizedSearch = normalizeSearch(search);
        String normalizedStatus = (status != null && !status.isBlank()) ? status.trim().toUpperCase() : null;

        Pageable pageable = PageRequest.of(clampedPage, clampedSize,
                Sort.by(Sort.Order.desc("order_date"), Sort.Order.desc("created_at")));

        return projectionRepository.findSalesHistorySlice(
                ctx.clientId(), ctx.orgId(), terminalId,
                effectiveFrom, effectiveTo,
                normalizedStatus, normalizedSearch,
                pageable);
    }

    /**
     * Returns currently open (non-completed) sale orders for the live orders panel.
     * Kept for backward compatibility — delegates to the incremental overload.
     */
    @Transactional(readOnly = true)
    public Slice<PosSaleSummaryView> getLiveSalesOrders() {
        TenantOrgContext ctx = resolveTenantOrgContext(null);

        Pageable pageable = PageRequest.of(0, 200,
                Sort.by(Sort.Order.desc("order_date"), Sort.Order.desc("created_at")));

        return projectionRepository.findLiveSalesOrders(ctx.clientId(), ctx.orgId(), pageable);
    }

    /**
     * Incremental live orders endpoint.
     * <ul>
     *   <li>{@code updatedAfter == null} → full snapshot (all live orders)</li>
     *   <li>{@code updatedAfter != null} → only orders changed since that time,
     *       plus IDs of orders that have moved to a terminal status</li>
     * </ul>
     *
     * @param updatedAfter server timestamp from the previous poll response
     * @return {@link LiveOrdersResponse} with orders, removedOrderIds, and serverTime
     */
    @Transactional(readOnly = true)
    public LiveOrdersResponse getLiveSalesOrdersIncremental(Instant updatedAfter, UUID cursorId) {
        TenantOrgContext ctx = resolveTenantOrgContext(null);
        Instant now = Instant.now();

        Pageable pageable = PageRequest.of(0, 200);

        if (updatedAfter == null) {
            // Full snapshot
            Slice<PosSaleSummaryView> slice = projectionRepository.findLiveSalesOrders(
                    ctx.clientId(), ctx.orgId(), pageable);
            return LiveOrdersResponse.fullSnapshot(slice.getContent(), now, slice.hasNext());
        }

        // Incremental delta
        LocalDateTime updatedAfterLocal = LocalDateTime.ofInstant(updatedAfter, ZoneOffset.UTC);

        Slice<PosSaleSummaryView> changedOrders = projectionRepository.findLiveSalesOrdersUpdatedAfter(
                ctx.clientId(), ctx.orgId(), updatedAfterLocal, cursorId, pageable);

        List<UUID> removedIds = projectionRepository.findRemovedLiveOrderIdsSince(
                ctx.clientId(), ctx.orgId(), updatedAfterLocal, cursorId);

        List<PosSaleSummaryView> content = changedOrders.getContent();
        boolean hasMore = changedOrders.hasNext();
        Instant nextCursorTime = null;
        UUID nextCursorId = null;
        if (!content.isEmpty() && hasMore) {
            PosSaleSummaryView last = content.get(content.size() - 1);
            if (last.getUpdatedAt() != null) {
                nextCursorTime = last.getUpdatedAt().atZone(ZoneOffset.UTC).toInstant();
            }
            nextCursorId = last.getOrderId();
        }

        return LiveOrdersResponse.incremental(content, removedIds, now, hasMore, nextCursorTime, nextCursorId);
    }

    @Transactional(readOnly = true)
    public LiveOrdersResponse getLiveSalesOrdersIncremental(String cursor) {
        if (cursor != null && !cursor.isBlank()) {
            LiveOrdersResponse.CursorPoint point = LiveOrdersResponse.decodeCursor(cursor);
            if (point != null) {
                return getLiveSalesOrdersIncremental(point.timestamp(), point.id());
            }
        }
        return getLiveSalesOrdersIncremental((Instant) null, null);
    }

    @Transactional(readOnly = true)
    public LiveOrdersResponse getLiveSalesOrdersIncremental(Instant updatedAfter) {
        return getLiveSalesOrdersIncremental(updatedAfter, null);
    }

    private String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
