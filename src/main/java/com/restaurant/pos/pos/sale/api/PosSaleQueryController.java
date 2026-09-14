package com.restaurant.pos.pos.sale.api;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.common.security.StaffAccess;
import com.restaurant.pos.pos.sale.query.PosSaleQueryService;
import com.restaurant.pos.pos.sale.query.PosSaleSummaryView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Slice;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.UUID;


import com.restaurant.pos.pos.sale.dto.SalesScreenDetails;

/**
 * Read-only POS Sales endpoints under {@code /api/v1/pos/sale}.
 */
@Slf4j
@StaffAccess
@RestController
@RequestMapping("/api/v1/pos/sale")
@RequiredArgsConstructor
@Validated
@Tag(name = "POS Sale (V2)", description = "High-performance POS sale query endpoints.")
public class PosSaleQueryController {

    private final PosSaleQueryService queryService;

    /**
     * Single-call POS Sales Screen Details aggregating configurations, categories,
     * payment modes, conditional initial products, and conditional tables.
     */
    @GetMapping("/sales-screen-details")
    @Operation(summary = "POS Sales Screen Details", description = "Returns aggregated configurations, categories, payment modes, and conditional tables/products in a single response.")
    public ResponseEntity<ApiResponse<SalesScreenDetails>> getSalesScreenDetails() throws Exception {
        final long start = System.nanoTime();
        SalesScreenDetails details = queryService.getSalesScreenDetails();
        log.info("POS sales screen details completed in {} ms", elapsedMs(start));
        return ResponseEntity.ok(ApiResponse.success(details));
    }

    /**
     * Loads POS configuration independently.
     */
    @GetMapping("/configurations")
    @Operation(summary = "POS Sale Configurations", description = "Returns all POS sales-related configurations, tax settings, hardware rules, and module flags.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Configurations loaded successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    public ResponseEntity<ApiResponse<com.restaurant.pos.common.dto.ConfigurationDto>> getConfigurations() {
        final long start = System.nanoTime();
        try {
            com.restaurant.pos.common.dto.ConfigurationDto config = queryService.getConfigurations();
            log.debug("POS configurations loaded in {} ms", elapsedMs(start));
            return ResponseEntity.ok(ApiResponse.success(config));
        } catch (Exception ex) {
            log.error("POS configuration query failed after {} ms", elapsedMs(start), ex);
            throw ex;
        }
    }


    /**
     * DB-level fast customer search bounded directly by SQL limit.
     * Prevents client-side full customer table iteration.
     */
    @GetMapping({"/customers", "/customers/search"})
    @Operation(summary = "Search Customers", description = "Returns fast, lightweight customer projections matching name or phone.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Customers returned successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    public ResponseEntity<ApiResponse<java.util.List<com.restaurant.pos.pos.sale.query.PosCustomerSummaryView>>> searchCustomers(
            @Parameter(description = "Customer name or phone search term") @RequestParam(required = false) String q,
            @Parameter(description = "Maximum results (max 50)") @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        final long start = System.nanoTime();
        try {
            java.util.List<com.restaurant.pos.pos.sale.query.PosCustomerSummaryView> customers =
                    queryService.searchCustomers(q, limit);
            log.debug("POS customer search completed in {} ms; query={}, limit={}, returned={}",
                    elapsedMs(start), q, limit, customers.size());
            return ResponseEntity.ok(ApiResponse.success(customers));
        } catch (Exception ex) {
            log.error("POS customer search failed after {} ms; query={}", elapsedMs(start), q, ex);
            throw ex;
        }
    }


    /**
     * Loads a page of POS products using keyset (cursor) pagination.
     * Guaranteed deterministic ordering by name ASC, id ASC.
     */
    @GetMapping("/products")
    @Operation(summary = "Load POS products", description = "Loads a limited page of POS products using cursor pagination.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Products page loaded successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    public ResponseEntity<ApiResponse<com.restaurant.pos.pos.sale.dto.PosProductPageResponse>> getProducts(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor) {
        final long start = System.nanoTime();
        try {
            UUID resolvedCategoryId = null;
            if (categoryId != null && !categoryId.isBlank() && !"all".equalsIgnoreCase(categoryId.trim())) {
                try {
                    resolvedCategoryId = UUID.fromString(categoryId.trim());
                } catch (IllegalArgumentException ex) {
                    resolvedCategoryId = queryService.findCategoryIdByName(categoryId.trim());
                }
            }
            com.restaurant.pos.pos.sale.dto.PosProductPageResponse response =
                    queryService.getProducts(resolvedCategoryId, search, limit, cursor);
            log.debug("POS product page loaded in {} ms; categoryId={}, resolvedCategoryId={}, searchPresent={}, requestedLimit={}, returned={}, hasMore={}",
                    elapsedMs(start), categoryId, resolvedCategoryId, search != null && !search.isBlank(), limit, response.items().size(), response.hasMore());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception ex) {
            log.error("POS product page failed after {} ms; categoryId={}, limit={}", elapsedMs(start), categoryId, limit, ex);
            throw ex;
        }
    }

    /**
     * High-performance sales history with zero entity hydration.
     * Uses a Slice-based response without expensive COUNT(*) queries.
     */
    @GetMapping("/history")
    @Operation(summary = "Sales History", description = "Paginated sale order history using lightweight native projections. No N+1 queries.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sales history loaded successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request parameters"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    public ResponseEntity<ApiResponse<SalesHistoryResponse>> getSalesHistory(
            @Parameter(description = "Start of date range (ISO-8601 UTC Instant)") @RequestParam(required = false) Instant fromDate,
            @Parameter(description = "End of date range (ISO-8601 UTC Instant)") @RequestParam(required = false) Instant toDate,
            @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 200)") @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @Parameter(description = "Filter by order status") @RequestParam(required = false) String status,
            @Parameter(description = "Search term (order no, customer name/phone)") @RequestParam(required = false) String q,
            @Parameter(description = "Filter by organization/branch ID") @RequestParam(required = false) UUID orgId,
            @Parameter(description = "Filter by terminal ID") @RequestParam(required = false) UUID terminalId) {

        final long start = System.nanoTime();
        try {
            Slice<PosSaleSummaryView> slice = queryService.getSalesHistory(
                    fromDate, toDate, page, size, status, q, orgId, terminalId);

            SalesHistoryResponse response = new SalesHistoryResponse(
                    slice.getContent(),
                    slice.getNumber(),
                    slice.getSize(),
                    slice.hasNext()
            );

            log.debug("POS sales history completed in {} ms; page={}, size={}, returned={}, hasMore={}",
                    elapsedMs(start), page, size, slice.getNumberOfElements(), slice.hasNext());

            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception ex) {
            log.error("POS sales history failed after {} ms; page={}, size={}, status={}, terminalId={}",
                    elapsedMs(start), page, size, status, terminalId, ex);
            throw ex;
        }
    }

    /**
     * Currently open (non-completed) sale orders for the live panel.
     * <p>
     * Supports incremental polling: pass {@code updatedAfter} (ISO-8601 Instant)
     * from the previous response's {@code serverTime} to receive only changed orders
     * and IDs of orders removed from live status.
     * </p>
     * <p>
     * Without {@code updatedAfter}, returns a full snapshot (backward compatible).
     * </p>
     */
    @GetMapping("/live")
    @Operation(summary = "Live Sales Orders", description = "Returns currently open sale orders. Supports incremental polling with updatedAfter parameter.")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Live sales loaded successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    public ResponseEntity<?> getLiveSalesOrders(
            @Parameter(description = "Opaque Base64 cursor from previous response's nextCursor")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "ISO-8601 Instant from previous response's serverTime for incremental updates (legacy)")
            @RequestParam(required = false) Instant updatedAfter,
            @Parameter(description = "UUID of last received order for deterministic cursor boundary handling (legacy)")
            @RequestParam(required = false) UUID cursorId) {
        final long start = System.nanoTime();
        try {
            if (cursor != null && !cursor.isBlank()) {
                com.restaurant.pos.pos.sale.dto.LiveOrdersResponse response =
                        queryService.getLiveSalesOrdersIncremental(cursor);
                log.debug("POS live sales (opaque cursor) completed in {} ms; changed={}, removed={}, hasMore={}",
                        elapsedMs(start), response.orders().size(), response.removedOrderIds().size(), response.hasMore());
                return ResponseEntity.ok(ApiResponse.success(response));
            }

            if (updatedAfter != null) {
                // Incremental mode
                com.restaurant.pos.pos.sale.dto.LiveOrdersResponse response =
                        queryService.getLiveSalesOrdersIncremental(updatedAfter, cursorId);
                log.debug("POS live sales (incremental) completed in {} ms; changed={}, removed={}, hasMore={}, updatedAfter={}",
                        elapsedMs(start), response.orders().size(), response.removedOrderIds().size(), response.hasMore(), updatedAfter);
                return ResponseEntity.ok(ApiResponse.success(response));
            }

            // Legacy full-snapshot mode
            Slice<PosSaleSummaryView> slice = queryService.getLiveSalesOrders();
            SalesHistoryResponse response = new SalesHistoryResponse(
                    slice.getContent(),
                    0,
                    slice.getSize(),
                    slice.hasNext()
            );
            log.debug("POS live sales (full snapshot) completed in {} ms; returned={}, hasMore={}",
                    elapsedMs(start), slice.getNumberOfElements(), slice.hasNext());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception ex) {
            log.error("POS live sales query failed after {} ms", elapsedMs(start), ex);
            throw ex;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Compact immutable API response for paginated sales queries.
     * Avoids HashMap allocation, string key lookup, and runtime type ambiguity.
     */
    public record SalesHistoryResponse(
            java.util.List<PosSaleSummaryView> items,
            int page,
            int size,
            boolean hasMore
    ) {}
}
