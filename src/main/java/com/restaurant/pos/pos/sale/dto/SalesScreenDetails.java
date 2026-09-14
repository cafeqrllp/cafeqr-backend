package com.restaurant.pos.pos.sale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Root payload returned by {@code GET /api/v1/sales-screen/details}.
 * Bundles only the necessary initial data for the POS Sales Screen in 1 single call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesScreenDetails {

    /** Server clock at response time. */
    private Instant serverTimestamp;

    /**
     * Configuration version for staleness detection.
     * Frontend can compare against its cached version and decide whether to refresh.
     */
    private Long configurationVersion;

    /** Compact screen configuration. */
    private SalesScreenConfiguration configuration;

    /** Active product categories. */
    @Builder.Default
    private List<ProductCategoryBean> categories = Collections.emptyList();

    /** Initial active products slice (empty if salesType is COUNTER). */
    @Builder.Default
    private List<ProductBean> products = Collections.emptyList();

    /** Active tables (populated only if tableEnabled is true). */
    @Builder.Default
    private List<TableBean> tables = Collections.emptyList();

    /** Configured active payment modes for Sales. */
    @Builder.Default
    private List<PaymentModeBean> paymentModes = Collections.emptyList();
}
